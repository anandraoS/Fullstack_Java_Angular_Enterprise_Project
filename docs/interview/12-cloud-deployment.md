# Cloud Deployment on AWS: EKS, RDS, MSK, ElastiCache, ECR, Secrets Manager, IRSA and the Trade-offs

This chapter covers the AWS reference architecture in `infrastructure/cloud/`: a design document plus a Terraform skeleton (`infrastructure/cloud/aws/terraform/*.tf`, 650 lines across eight files) and adaptation tables for Azure and GCP. **Status, stated up front: this is a design and an infrastructure-as-code skeleton that was statically validated at most. No cloud account was used, nothing was planned or applied, no cost or performance figure is claimed, and nothing was deployed.** `infrastructure/cloud/README.md` says so ("Nothing here has been applied, no cloud account was used, no cost or performance figures are claimed. `terraform validate` status is recorded in `scripts/validate-infra.sh` output (PASS only if it actually ran)"). The repository contains no the docs/validation folder record of a `terraform validate` run, so in an interview say "written to pass `terraform fmt` and `validate`, checked by a script that reports SKIP when the tool is missing, never planned or applied", not "validated". The numbers in the variable defaults (instance classes, node counts) are starting points, not sizing.

## Big picture and kid-friendly analogy

Imagine moving a school from a rented classroom into its own campus.

* **A VPC** is the fenced school grounds. **Subnets** are zones inside the fence: the public car park by the gate (load balancers and NAT), the classrooms (EKS nodes and pods, no direct way in from the street), and the locked records room (databases, no route to the outside at all).
* **Availability zones** are three separate buildings on the campus, each with its own power and plumbing. If one floods, the other two keep teaching.
* **EKS** is a school office that hires and replaces teachers (nodes) and decides which classroom (pod) each lesson goes in. **ECR** is the school's own book printing room: each edition is stamped and cannot be altered later.
* **RDS** is the records office with a second clerk in another building who copies every entry immediately (Multi-AZ). **MSK** is the school's message boards in each building, copied three ways. **ElastiCache** is a whiteboard for quick notes that may be wiped without harm.
* **Secrets Manager** is the head teacher's locked cabinet; **IRSA** is a visitor badge that says "this particular classroom may open drawer 7 of the cabinet and nothing else", instead of giving every teacher a copy of the master key.
* **ALB** is the reception desk at the gate that checks the visitor and sends them to the right classroom.
* **Cost vs availability** is the question of how many spare buildings you can afford to keep warm, and **DR** (disaster recovery) is the plan if the whole campus floods.

| Analogy | AWS service or concept |
|---|---|
| Fenced grounds with zones | VPC, public / private / data subnets |
| Three separate buildings | Three availability zones (`az_count = 3`) |
| School office placing lessons | EKS control plane, managed node group |
| Stamped book editions | ECR with immutable tags and scan on push |
| Records office with a live copy | RDS PostgreSQL Multi-AZ |
| Copied message boards | MSK, replication factor 3, min ISR 2 |
| Wipeable whiteboard | ElastiCache Redis (rate-limit buckets only) |
| Locked cabinet and per-room badge | Secrets Manager, IRSA |
| Reception desk | ALB with ACM TLS and WAF |

## Beginner tier

### Example 1: a three-tier network, and why the data tier has no internet route

`infrastructure/cloud/aws/terraform/network.tf` creates a VPC (default `10.20.0.0/16`) and, per availability zone, three subnets carved out with `cidrsubnet`:

```hcl
# Public subnets: load balancers + NAT only.
resource "aws_subnet" "public" {
  count                   = var.az_count
  # ...
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false
}

# Private subnets: EKS nodes and pods.
resource "aws_subnet" "private" {
  count             = var.az_count
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 4)
  # ...
}

# Data subnets: RDS, ElastiCache, MSK. No route to the internet.
resource "aws_subnet" "data" {
  count             = var.az_count
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 8)
  # ...
}
```

The structure is the standard three-tier pattern. Only the public tier has a route to the internet gateway. The private tier reaches the internet outbound through a NAT gateway (`aws_nat_gateway`; `var.single_nat_gateway` selects one shared gateway, described in the variable as "cheaper, not AZ-resilient", versus one per AZ, the default `false`). The data tier has its own route table and no default route, so a compromised pod can reach the database only through the security-group path, and the database cannot be reached or reach out. Subnet tags `kubernetes.io/role/elb` and `.../internal-elb` tell the AWS Load Balancer Controller where to place internet-facing and internal load balancers.

Beginners should also know what these services do for this application. The Kubernetes manifests are cloud-neutral; AWS contributes the managed data tier and the cluster. The prod overlay (`infrastructure/kubernetes/overlays/prod/kustomization.yaml`) points the ConfigMap at literal placeholder endpoints such as `oms-pg.cluster-PLACEHOLDER.eu-west-1.rds.amazonaws.com`, which Terraform outputs (`rds_endpoint`, `msk_bootstrap_brokers_iam`, `redis_primary_endpoint` in `outputs.tf`) are meant to supply after a real apply.

### Example 2: managed data tier with encryption and reachability limited to EKS

`infrastructure/cloud/aws/terraform/data.tf` defines RDS PostgreSQL 16, ElastiCache Redis and MSK. The RDS block:

```hcl
resource "aws_db_instance" "postgres" {
  identifier                          = "${var.name}-${var.environment}"
  engine                              = "postgres"
  engine_version                      = "16"
  storage_type                        = "gp3"
  storage_encrypted                   = true
  username                            = "oms_admin"
  manage_master_user_password         = true # RDS generates the password and stores it in Secrets Manager: never in code/state
  multi_az                            = true
  vpc_security_group_ids              = [aws_security_group.rds.id]
  publicly_accessible                 = false
  backup_retention_period             = 14
  deletion_protection                 = true
  skip_final_snapshot                 = false
  iam_database_authentication_enabled = true
  performance_insights_enabled        = true
}
```

Read it as a checklist you can recite: encrypted storage, no public access, Multi-AZ synchronous standby, 14-day automated backups (which also enable point-in-time restore), deletion protection plus a final snapshot, a master password generated and held by RDS in Secrets Manager (so it never appears in code or in Terraform state), and Performance Insights (the managed equivalent of the `pg_stat_statements` workflow in chapter 7). The security group allows port 5432 only from the EKS cluster security group (`security_groups = [local.eks_sg]`), not from CIDR ranges, so access follows the cluster's identity, not an address plan. Redis and MSK have the same pattern with their own security groups. Redis is a replication group with two nodes, automatic failover, Multi-AZ, encryption at rest and in transit, and an `auth_token` that comes from a variable (the README says to supply it as `TF_VAR_redis_auth_token` from a secret store); Redis is optional for the app (rate-limit buckets only, and the application degrades to in-memory buckets if it is down, per the shared contract).

MSK is the most opinionated block. `aws_msk_configuration` sets `auto.create.topics.enable=false`, `default.replication.factor=3`, `min.insync.replicas=2` and `num.partitions=3`, which matches the contract's production topology (replication 3 in production against 1 locally). `min.insync.replicas=2` with producer `acks=all` (the outbox relay's setting) means a write survives the loss of one broker without data loss, and the producer fails (rather than silently losing data) if two brokers are down. Broker count equals `az_count`, one broker per AZ.

## Intermediate tier

### Example 1: IRSA, least privilege without static keys

Pods that need AWS APIs should not hold access keys. EKS exposes an OIDC issuer; IAM roles for service accounts (IRSA) lets a specific Kubernetes ServiceAccount assume a specific IAM role. `infrastructure/cloud/aws/terraform/eks.tf` wires it:

```hcl
data "aws_iam_policy_document" "app_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:oms-${var.environment}:oms-app"]
    }
  }
}
```

The `sub` condition is the security-critical line: only the ServiceAccount named `oms-app` in namespace `oms-<environment>` can assume the role. Without that condition, any pod in the cluster could. The attached policy grants `secretsmanager:GetSecretValue` and `DescribeSecret` only on `arn:aws:secretsmanager:<region>:<account>:secret:oms/<env>/*` and a list of `kafka-cluster:*` data-plane actions on the MSK cluster, its topics and groups. The output `app_irsa_role_arn` is documented as "Annotate the oms-app ServiceAccount with this role ARN", and the Deployments use `serviceAccountName: oms-app`. The flow in plain words: a pod presents a projected, short-lived service-account token to STS, STS verifies it against the cluster's OIDC provider and the trust condition, and returns temporary credentials. Newer alternatives exist (EKS Pod Identity); the concept is the same.

Secrets reach pods through the External Secrets Operator (`infrastructure/kubernetes/examples/externalsecret.example.yaml`): an `ExternalSecret` named `oms-secrets` with `refreshInterval: 1h` maps remote keys like `oms/prod/jwt` and `oms/prod/order-db` into the Kubernetes Secret `oms-secrets`, using a `ClusterSecretStore` that authenticates with IRSA. `secrets_observability.tf` creates only the Secrets Manager containers (`oms/prod/jwt`, `oms/prod/redis`) and the comment is deliberate: "VALUES are set out-of-band (console/CLI/rotation lambda) so they never reach Terraform state". Honest gaps to volunteer: the ExternalSecret example references per-service database secrets (`oms/prod/order-db`, `oms/prod/inventory-db`, and so on) that the Terraform does not create, and there is no job in the repository that creates the four databases and four roles on RDS (the Compose and dev-dependency equivalents exist; `data.tf` only says they are "created by a migration job/bootstrap"). Both are items to build before a real deployment.

### Example 2: ECR, the delivery path, and what the pipeline really does

`infrastructure/cloud/aws/terraform/ecr.tf` provisions one repository per service with immutability and scanning:

```hcl
resource "aws_ecr_repository" "svc" {
  for_each             = toset(var.services)
  name                 = "${var.name}/${each.value}"
  image_tag_mutability = "IMMUTABLE" # a tag always means the same bytes; deploy by digest
  force_delete         = false

  image_scanning_configuration {
    scan_on_push = true
  }
}
```

Immutable tags prevent a tag such as `1.0.0` from being silently overwritten with different bytes, which is why the Kubernetes README says production should reference images by digest. A lifecycle policy keeps the last 30 images. The design document describes CI authenticating to AWS via GitHub OIDC (no long-lived keys). The workflow in the repo does something different, and you should say so: `.github/workflows/ci.yml` builds and scans images with Trivy and pushes to GHCR (`ghcr.io/example-org/...`) on `main` and tags only, and its `deploy-test` job is a labelled placeholder that needs a base64 kubeconfig secret for a non-production cluster and exits early if it is absent; it requests `id-token: write` but explicitly does not use it yet. So ECR exists in Terraform and GHCR exists in CI, and the manifests reference GHCR placeholders. The reconciliation (push to ECR using a GitHub OIDC role, deploy by digest through a GitOps controller) is the intended direction, not the current state. The CI definition itself was never executed on a runner (`infrastructure/README.md`).

## Advanced tier

### Example 1: multi-AZ, availability, and what actually fails over

Walk through failure domains, because this is the heart of the trade-off conversation. All three data stores in the skeleton are multi-AZ, and EKS node groups span the three private subnets:

* **A pod or node dies.** The Deployment replaces the pod; PDBs and `maxUnavailable: 0` keep capacity during drains; the prod overlay adds a zone topology spread constraint so replicas sit in different AZs (`infrastructure/kubernetes/overlays/prod/kustomization.yaml`).
* **An AZ is lost.** EKS nodes in the other two zones keep serving. RDS Multi-AZ fails over to the standby in another AZ by flipping the DNS endpoint, which causes a short outage and dropped connections; HikariCP's `max-lifetime` and `connection-timeout` settings (chapter 7) and the saga and Kafka retry logic absorb it, and the transactional outbox plus idempotent consumers mean in-flight events are redelivered, not lost. ElastiCache fails over to its replica; the application degrades to in-memory rate limiting if Redis is unavailable. MSK with three brokers, replication 3 and `min.insync.replicas=2` tolerates one broker or AZ loss for both reads and writes.
* **A region is lost.** Not handled. `docs/governance/HLD.md` lists multi-region as out of scope, and the risk register (R-18) records that the topology can be mistaken for HA and that RPO and RTO are "unmeasured". Be precise: Multi-AZ is high availability, not disaster recovery.

Honest uncertainty to state: **RPO and RTO were not measured**; the numbers you would quote come from AWS service documentation (RDS Multi-AZ failover is typically on the order of a minute or two; automated backups support point-in-time restore to within about five minutes), which you must verify for the engine version and configuration in use, not from this repository. The repository also does not contain a failover rehearsal, a game-day record or a restore test.

Application-level details that determine real availability, all present in the codebase: Kafka consumers resume from committed offsets after a broker or pod change; the outbox guarantees that state changes and events are committed together; the gateway has a circuit breaker and returns 503 `UPSTREAM_UNAVAILABLE` with `Retry-After`; `/api/v1/ops/health` aggregates readiness of the four services; and readiness probes remove unready pods. Infrastructure redundancy without these behaviours would still produce visible errors during a failover.

### Example 2: cost, availability and DR trade-offs, as a decision framework

The repository makes several explicit cost-versus-resilience choices; present them as trade-offs rather than recommendations. No dollar figures appear because the repo claims none and prices change; reason in relative terms.

| Decision | Cheaper option | More resilient option | What this repo chose |
|---|---|---|---|
| NAT gateways | one shared (`single_nat_gateway = true`) | one per AZ | per AZ by default; variable allows one for non-prod |
| RDS topology | one instance, four databases | one instance per service | one shared instance with logical isolation (README: cheaper and simpler, couples blast radius and noisy-neighbour risk) |
| RDS Multi-AZ | single AZ | Multi-AZ standby (roughly doubles instance cost) | `multi_az = true` |
| Kafka | fewer or smaller brokers, replication 1 | three brokers, RF 3, min ISR 2 | three brokers, RF 3, ISR 2 (local runs use RF 1) |
| Redis | none (in-memory buckets) | replicated with failover | replicated, but the app does not depend on it |
| Nodes | fewer, spot capacity | three nodes minimum, on-demand | min 3 / max 9 `m6i.large` defaults |
| Observability | self-hosted in cluster | AMP, AMG, CloudWatch, X-Ray | managed services in the skeleton |
| Environments | prod only | separate dev, test, prod accounts or clusters | Kustomize overlays; Terraform `environment` variable; no remote state configured |

Reasoning you can add. NAT gateways have an hourly charge and a per-GB processing charge, so a chatty private tier that pulls images and calls external APIs can make NAT a notable line item, and VPC endpoints (for ECR, S3 and Secrets Manager) are a common optimisation. MSK brokers and Multi-AZ databases are typically the dominant fixed costs of such a design, which is why the README's "one RDS instance, four databases" decision is a real saving, and why splitting per service later is described as a "mechanical" migration thanks to separate databases and roles per service. Autoscaling (the HPA and KEDA manifests) matters for pods, but node-level scaling (Cluster Autoscaler or Karpenter) is not in the skeleton and is listed among the add-ons to install before real use, together with the CNI network-policy mode, EBS CSI driver, KEDA, External Secrets and kube-prometheus-stack. For DR, the proportional ladder is: backups only (cheapest, slow recovery), then cross-region backup copies, then pilot light (Terraform plus replicated data, scaled-down compute), warm standby, and active-active. This repository stops at the first rung plus in-region Multi-AZ, and a candidate should be able to say which rung a given business requirement would need and what extra pieces (cross-region read replica or snapshot copy, MSK replication, DNS failover, runbooks, rehearsals) it would add.

A configuration inconsistency to flag as a real finding: Terraform provisions MSK with IAM authentication only (`client_authentication { sasl { iam = true } }`, brokers on the TLS and IAM ports, output `msk_bootstrap_brokers_iam`), but the prod overlay's `KAFKA_BOOTSTRAP_SERVERS` uses port `9096`, which is SASL/SCRAM, and the NetworkPolicy opens 9094, 9096 and 9098. Using IAM authentication from the Java services would also need the AWS MSK IAM auth library and matching `sasl.*` properties, and a search of the services' build files and configuration finds neither. So the Kafka path from EKS to MSK is not coherent end to end yet; it is exactly the kind of gap that only a real apply and smoke test would reveal.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| AWS reference and trade-off notes | `infrastructure/cloud/README.md`, `infrastructure/cloud/diagrams/aws-reference.mmd` | design plus Azure and GCP adaptation tables; "what stays identical everywhere" |
| Network | `infrastructure/cloud/aws/terraform/network.tf` | VPC, three tiers per AZ, NAT per AZ (or single), data tier without internet route |
| EKS, KMS, IRSA | `infrastructure/cloud/aws/terraform/eks.tf` | private endpoint, secrets envelope encryption, audit logs, OIDC provider, app role |
| Data tier | `infrastructure/cloud/aws/terraform/data.tf` | RDS PostgreSQL 16 Multi-AZ, ElastiCache replication group, MSK with IAM auth |
| Registry | `infrastructure/cloud/aws/terraform/ecr.tf` | immutable tags, scan on push, keep last 30 |
| Secrets and observability | `infrastructure/cloud/aws/terraform/secrets_observability.tf` | Secrets Manager containers, AMP workspace, AMG workspace, CloudWatch log group |
| Variables and versions | `variables.tf`, `versions.tf`, `outputs.tf` | AWS provider `~> 5.70`, Terraform `>= 1.6.0`, remote state backend left commented |
| External secrets | `infrastructure/kubernetes/examples/externalsecret.example.yaml` | ESO with IRSA, 1 h refresh |
| Prod overlay with placeholder endpoints | `infrastructure/kubernetes/overlays/prod/kustomization.yaml` | RDS, MSK, Redis hosts are `PLACEHOLDER` |
| Pipeline | `.github/workflows/ci.yml`, `infrastructure/ci/Jenkinsfile`, `docs/adr/0008-ci-cd-and-deployment.md` | GHCR push, placeholder deploy; never executed |
| Validation | `scripts/validate-infra.sh` | `terraform fmt -check` and `validate` when tool and network available, otherwise SKIP |

Remaining detail from `eks.tf` worth noting: the cluster has private endpoint access always on and public access only when `eks_public_access_cidrs` is non-empty (default empty, so no public API endpoint), a customer-managed KMS key with rotation enabled encrypts Kubernetes Secrets, control-plane `api`, `audit` and `authenticator` logs are enabled, and node groups update one node at a time (`max_unavailable = 1`). `versions.tf` leaves the S3 backend commented out with the note "Remote state: configure per environment, never commit state or backend credentials", so state locking and remote state are an explicit to-do.

### Demonstration-only vs production-grade

Production-grade choices present in the skeleton: three-tier network with a data tier that has no internet route; security groups that admit only the EKS cluster security group; encryption at rest and in transit for every store; RDS-managed master password; Multi-AZ everywhere; private EKS endpoint with KMS-encrypted secrets and audit logging; IRSA with a `sub` condition; immutable, scanned ECR repositories; Secrets Manager values kept out of Terraform state; no long-lived keys by design.

Demonstration-only, unproven or missing: nothing was applied, planned or run against AWS; no remote state or locking; no environment-specific tfvars; the ALB, WAF, Route 53 and ACM resources are not in the skeleton (the README says they are managed by the ingress controller and must be added); node autoscaling, CNI network-policy mode, EBS CSI, KEDA, External Secrets and kube-prometheus-stack are listed as to-install; no bootstrap for the four databases and roles; per-service secrets referenced by the ExternalSecret are not created; the MSK authentication mismatch described above; the CI deploy job is a placeholder and pushes to GHCR rather than ECR; Azure and GCP sections are adaptation tables, not code; RPO and RTO are unmeasured; no cost estimate exists; the observability modules (AMP, AMG) are declared but the Prometheus remote-write configuration is not wired in this repository.

## Common mistakes and gotchas

1. **Calling Multi-AZ "disaster recovery".** It is high availability within a region. DR needs a cross-region plan and tested restores.
2. **Untested backups.** A backup you have never restored is a hope. Schedule restore drills and record the measured RTO.
3. **IRSA trust policy without the `sub` condition.** Any pod could assume the role.
4. **Over-broad IAM.** Scope `secretsmanager:GetSecretValue` to a prefix and MSK actions to the cluster, topics and groups, as the policy here does.
5. **Secrets in Terraform state.** Terraform stores resource attributes in state; use `manage_master_user_password` or out-of-band secret values.
6. **Putting databases in public subnets, or opening security groups to CIDRs.** Use security-group references and a no-route data tier.
7. **One NAT gateway in production.** A single AZ failure then removes outbound access for the private subnets in other AZs.
8. **Mutable image tags and unscanned images.** Use immutable tags, scan on push, deploy by digest.
9. **Sizing from defaults.** Instance classes in variables are placeholders; size from measured load.
10. **Kafka auth mismatch between infrastructure and clients.** The broker authentication mode, bootstrap port, client library and properties must agree.
11. **Forgetting node-level scaling.** HPA adds pods; without Cluster Autoscaler or Karpenter the pods pend.
12. **Assuming the cloud diagram equals the deployed state.** Terraform skeletons that were never applied hide missing resources (ALB, DNS, bootstrap jobs).
13. **Ignoring data transfer and NAT costs.** Cross-AZ traffic and NAT processing are common surprise line items; use VPC endpoints where appropriate.
14. **Single shared database without isolation discipline.** If services share an instance, keep one database and role per service and no cross-database access, so splitting later is mechanical.

## Quick-recall cheat sheet

* Layout: VPC `10.20.0.0/16`, three AZs, public (ALB, NAT), private (EKS), data (RDS, Redis, MSK; no internet route).
* EKS: private endpoint, KMS-encrypted secrets, audit logs, managed node group (3 to 9 nodes by default), OIDC provider for IRSA.
* IRSA: trust policy `AssumeRoleWithWebIdentity` with `sub = system:serviceaccount:oms-<env>:oms-app`; policy scoped to `oms/<env>/*` secrets and the MSK cluster.
* RDS: PostgreSQL 16, Multi-AZ, encrypted, 14-day backups, deletion protection, RDS-managed master password, IAM auth enabled, one instance with four logical databases.
* MSK: three brokers, RF 3, min ISR 2, `auto.create.topics.enable=false`, TLS plus IAM auth (mismatch with prod overlay port 9096 noted).
* Redis: replication group, failover, TLS plus AUTH, optional for the app.
* ECR: immutable tags, scan on push, last 30 images; CI actually pushes to GHCR; deploy job is a placeholder.
* Secrets: Secrets Manager containers in Terraform, values out-of-band, External Secrets Operator with IRSA, `refreshInterval: 1h`.
* Honest status: static validation only (`terraform fmt/validate` where tools ran), never planned or applied, no RPO/RTO measured, no cost numbers.
* Trade-offs to articulate: NAT per AZ vs one, shared RDS vs per-service, Multi-AZ cost, MSK RF 3 vs 1, HA vs DR ladder (backup, pilot light, warm standby, active-active).
* Azure and GCP: same Kustomize base; change registry, DB URL and SSL mode, Kafka endpoint and auth, secret store provider.
