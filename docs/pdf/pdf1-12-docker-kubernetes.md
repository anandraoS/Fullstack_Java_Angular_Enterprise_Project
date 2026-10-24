## Honest status first

Docker was **not available** on the development machine. Dockerfiles, Compose files, Kubernetes manifests and Terraform were written and validated statically (kustomize build, kubeconform, terraform validate, compose config, checked by `scripts/validate-infra.sh` and CI job `infra-validate`) where those tools could be fetched. Whether any image was actually built or any container actually ran is stated in the Evidence section; do not assume it. Anything marked "reference" below was never applied to a live cluster.

## Container images

One Dockerfile per component in `infrastructure/docker/`. Key properties of `order-service.Dockerfile`:

* Two stages: the Spring Boot fat jar is split into layers with `-Djarmode=tools extract --layers`, so dependency layers are cached and only the small application layer changes per build.
* JRE-only Alpine base pinned to an exact version tag (digest pinning is documented as a production step).
* Non-root numeric user (uid 10001) so Kubernetes `runAsNonRoot` can verify it.
* `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` and graceful shutdown (`SIGTERM` reaches the JVM because of `exec`).
* `HEALTHCHECK` on `/actuator/health/readiness` for Docker and Compose.

{{code:infrastructure/docker/order-service.Dockerfile|USER 10001|10|dockerfile}}

## Docker Compose

`infrastructure/compose/docker-compose.yml` runs PostgreSQL 16 (four databases created by `postgres/init/01-create-databases.sh`), Kafka in KRaft mode with topics created by `kafka/create-topics.sh`, Redis, the four services, the gateway and the frontend; `docker-compose.observability.yml` adds Prometheus, Grafana, Loki, Promtail, Tempo and the OpenTelemetry collector. The no-Docker local path (`scripts/run-local.sh`) uses the `local-stack` module with embedded Kafka and H2 and is what was actually executed.

## Kubernetes

Layout (`infrastructure/kubernetes/`): `base/` (namespace, one Deployment and Service per component, ConfigMap, Ingress, NetworkPolicies, ServiceAccount), `overlays/dev|test|prod` (Kustomize patches for replicas, resources, images and settings), `dev-dependencies/` (PostgreSQL, Kafka, Redis for dev clusters only), `keda/` (Kafka-lag autoscaling), `monitoring/` (ServiceMonitor and PrometheusRule), `examples/` (secret templates; no real secrets).

| Concern | What the manifests do |
|---|---|
| Zero-downtime rollout | `maxUnavailable: 0`, readiness gate before traffic |
| Slow JVM start | `startupProbe` so liveness does not kill a booting pod (Flyway on first boot) |
| Probes | Spring Boot liveness and readiness groups under `/actuator/health/*` |
| Pod security | `runAsNonRoot`, container `securityContext`, dedicated ServiceAccount |
| Resources | requests and limits per container; JVM sized with percentage flags |
| Network | NetworkPolicies, default deny with explicit allows |
| Config and secrets | ConfigMap for non-secret settings; secrets from a Secret or External Secrets (templates only) |
| Autoscaling | HorizontalPodAutoscalers scale 2 to 6 replicas on CPU; the KEDA ScaledObject for Kafka lag is an example file that is not part of any overlay |
| Rollback | `kubectl rollout undo` and the decision tree in `docs/governance/deployment-and-rollback.md` |

{{code:infrastructure/kubernetes/base/order-service.yaml|startupProbe|26|yaml}}

## Rollout and database changes

Migrations follow expand/contract so the previous version keeps working during a rolling update and rollback is safe (see deployment-and-rollback). Kafka consumers rebalance on pod restart; offsets are committed after processing, so a restarted consumer resumes without loss and idempotent consumers absorb redelivery.

## AWS reference

`infrastructure/cloud/aws/terraform/` defines VPC across three availability zones with public, private and data subnets, EKS with managed node groups, RDS PostgreSQL Multi-AZ, MSK, ElastiCache, ECR, Secrets Manager and IRSA. It is a reference: `terraform validate` is the strongest check performed; no `apply` was run and there is no cost estimate in this repository. Known gap recorded by the documentation review: the production overlay expects a SASL/SCRAM listener while the Terraform enables IAM authentication for MSK, and no MSK IAM client library is in the service poms. Resolve this before any real deployment.

## Trade-offs

* Kustomize over Helm: fewer moving parts, plain YAML diffs, less templating power.
* Lag-based scaling (KEDA) cannot help beyond the partition count: 3 partitions means at most 3 useful consumers per group, while the HPA ceiling is 6 replicas.
* Managed Kafka and PostgreSQL cost more than self-hosting but remove the hardest operational work.
