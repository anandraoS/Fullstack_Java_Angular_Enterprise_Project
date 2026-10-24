# Kubernetes (Kustomize)

Status: manifests render and pass `kubeconform -strict` for dev/test/prod overlays and the monitoring add-on (core kinds validated, CRDs skipped: no schema available offline). **Nothing was applied to a cluster.** Probes, NetworkPolicies, PDBs, HPA behaviour and the stateful dev dependencies are unverified at runtime.

```
base/                 Namespace (PSA restricted), ServiceAccount, ConfigMap, 5 Java Deployments+Service+PDB+HPA, frontend, Ingress, NetworkPolicies
overlays/dev|test|prod   namespace, images, replicas, profile/endpoints/hosts patches
dev-dependencies/     in-cluster Postgres/Kafka(KRaft)/Redis StatefulSets + topic Job (dev and test only)
monitoring/           ServiceMonitor + PrometheusRule (generated from observability/prometheus/rules) - needs Prometheus Operator CRDs
keda/                 ScaledObject examples (Kafka lag) - not wired into any overlay
examples/             ExternalSecret example, secret SHAPE example (NOT FOR USE), create-dev-secret.sh
```

**Stateful services decision:** dev and test run Postgres/Kafka/Redis in-cluster (single replica, no HA, no backups, plaintext inside the namespace) via `dev-dependencies/`. Prod uses managed services (RDS, MSK, ElastiCache; see `infrastructure/cloud`) and the prod overlay carries placeholder endpoints only.

## Secrets

Manifests only contain `secretKeyRef` to a Secret named `oms-secrets` (keys: `jwt-secret`, `demo-password` (dev profile only, optional), `postgres-password`, `<svc>-db-username|password` for order/inventory/fulfillment/notification, `redis-password`). No Secret object is rendered by any overlay (checked by `validate-infra.sh`).
* dev/test: `infrastructure/kubernetes/examples/create-dev-secret.sh oms-dev` generates random values and streams them to kubectl (nothing on disk).
* prod: External Secrets Operator (`examples/externalsecret.example.yaml`) from AWS Secrets Manager, or Sealed Secrets (`kubeseal` output is safe to commit; the plain Secret and the controller key are not).
* `examples/secret.example.yaml` documents the shape with `NOT-A-REAL-SECRET` values and must never be applied.

## Deploy

```bash
kubectl apply -k infrastructure/kubernetes/overlays/dev --dry-run=client      # render check
kubectl create namespace oms-dev   # or let the overlay create it
infrastructure/kubernetes/examples/create-dev-secret.sh oms-dev
kubectl apply -k infrastructure/kubernetes/overlays/dev
kubectl -n oms-dev rollout status deploy/api-gateway
```
Set image tags with `kustomize edit set image ghcr.io/example-org/oms/order-service=<registry>/order-service:<semver>-<sha>` (CI does this; prod should use digests).

## Rollout and rollback snippet

Rolling update: `maxSurge: 1, maxUnavailable: 0`, readiness gates traffic, `preStop: sleep 10` + `terminationGracePeriodSeconds: 45` + Spring graceful shutdown (25 s) drain in-flight requests and stop Kafka listeners before SIGKILL. Because Kafka consumers are idempotent (`processed_events`) and producers use the outbox, a pod killed mid-message causes a redelivery, not data loss or a duplicate business effect.

```bash
# roll out a new version
kustomize edit set image ghcr.io/example-org/oms/order-service=REG/order-service:1.0.1-ab12cd34   # in overlays/<env>
kubectl apply -k infrastructure/kubernetes/overlays/<env>
kubectl -n oms-<env> rollout status deploy/order-service --timeout=300s

# watch (errors, latency, lag): Grafana "OMS - API RED" and "OMS - Kafka Messaging"; or
kubectl -n oms-<env> get pods -w

# roll back the Deployment to the previous ReplicaSet
kubectl -n oms-<env> rollout undo deploy/order-service
kubectl -n oms-<env> rollout history deploy/order-service        # revisionHistoryLimit: 5

# GitOps style: revert the image-tag commit and re-apply; never edit live objects.
```
Rollback caveats: schema changes must be backward compatible (expand/contract with Flyway; the previous version must run against the new schema), event schemas are additive-only (tolerant reader), so an old pod can read new events. A rollback does not undo business effects of orders processed by the new version.

## Autoscaling

CPU HPA in base (2-6 replicas, scale-down stabilisation 5 min). For consumers, KEDA on Kafka lag is the better signal (`keda/`): remove the base HPA for that Deployment first, and cap `maxReplicaCount` at the partition count (3).

## Network model

Default deny (ingress+egress) -> DNS egress; ingress-nginx -> gateway/UI; gateway -> services (+ order -> inventory); services -> Postgres/Kafka/Redis; everything -> OTLP collector; monitoring namespace -> actuator ports. The managed-service `ipBlock` CIDR `10.0.0.0/8` is a **placeholder**: tighten to the data subnets. Needs a CNI that enforces NetworkPolicy, and namespaces named `ingress-nginx`, `monitoring`, `observability` (adjust selectors otherwise).
