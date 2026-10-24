# Infrastructure, CI/CD, observability and run scripts

Read `docs/architecture/00-shared-contract.md` first. This directory contains everything needed to build, ship and watch the system.

| Path | Contents | Verified how |
|---|---|---|
| `docker/` | one multi-stage Dockerfile per Java service + gateway (layered jars, non-root uid 10001, JVM container flags, healthcheck, graceful shutdown) | **not built** (no Docker) |
| `compose/` | `docker-compose.yml` (postgres, kafka KRaft + topic job, redis, 4 services, gateway, frontend) and `docker-compose.observability.yml` | YAML parsed + structural checks only; **not run** |
| `kubernetes/` | Kustomize base + dev/test/prod overlays, monitoring, KEDA, secret examples | `kustomize build` + `kubeconform -strict`: see `scripts/validate-infra.sh` |
| `cloud/` | AWS reference (EKS, MSK, RDS, ElastiCache, ECR, Secrets Manager, AMP/AMG), Azure/GCP adaptation tables, Terraform skeleton | `terraform fmt` + `validate` (no plan/apply, no credentials) |
| `ci/Jenkinsfile` | declarative equivalent of `.github/workflows/ci.yml` | not executed, not parsed by Jenkins |
| `../.github/workflows/ci.yml` | CI pipeline | YAML parses; **not executed** |
| `../observability/` | Prometheus, alert rules, Grafana dashboards, OTel collector, Loki/Tempo | JSON/YAML/rule-structure checks; promtool unavailable |
| `../performance/` | k6 scripts | `node --check` only; **not executed, no results** |

Quick start without Docker: `scripts/run-local.sh` (see header). Validate everything available: `node scripts/fetch-validation-tools.mjs kustomize kubeconform terraform && bash scripts/validate-infra.sh`.

## Assumptions about modules built by others (reconcile if they differ)

* Jar names `<module>-1.0.0.jar`: `services/{order,inventory,fulfillment,notification}-service/target`, `gateway/target/api-gateway-1.0.0.jar`, `services/local-stack/target/local-stack-1.0.0.jar` (must be an executable Boot jar for `run-local.sh`).
* Boot 3.3+ layered jars (`-Djarmode=tools extract --layers`), actuator health groups `liveness`/`readiness` enabled (`management.endpoint.health.probes.enabled=true`), `/actuator/prometheus` exposed.
* Configuration is environment driven through Spring relaxed binding: `SPRING_DATASOURCE_URL|USERNAME|PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_DATA_REDIS_HOST|PASSWORD`, `OMS_REDIS_ENABLED`, `OMS_JWT_SECRET`, `OMS_DEMO_PASSWORD`, `SERVER_PORT`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`.
* Gateway static route targets and CORS: `OMS_GATEWAY_ROUTES_{ORDER,INVENTORY,FULFILLMENT,NOTIFICATION}_URI`, `OMS_GATEWAY_CORS_ALLOWED_ORIGINS`; order-service -> inventory base URL `OMS_INVENTORY_BASE_URL`. **These property names are guesses** (the contract only says "static URIs from properties"); rename in `compose/`, `kubernetes/base/configmap.yaml` if the gateway uses others.
* Profiles: `dev` (token endpoint + seed), `json-logs`, `local` (no Docker: H2 + embedded Kafka), `prod` (no defaults for secrets).
* The frontend Dockerfile (owned by the frontend module) serves on 8080 as a non-root user.
* Histogram metrics for p95/p99 need `management.metrics.distribution.percentiles-histogram.http.server.requests=true` and for `oms.order.processing.duration`.

## Security posture (summary)

No secret values are committed: `.env.example` has `CHANGE_ME` placeholders, Compose uses `${VAR:?}` so it refuses to start with unset secrets, Kubernetes uses `secretKeyRef` only, Terraform generates the RDS password in Secrets Manager and takes the Redis token from `TF_VAR_redis_auth_token`. Containers run non-root with read-only root filesystems, dropped capabilities and `no-new-privileges`; ports bind to 127.0.0.1 in Compose; Kafka/Postgres/Redis are not published to the host. Tradeoffs and leftovers: Compose Kafka is plaintext (in-network only); Promtail mounts the Docker socket (laptop demo only); image tags are version-pinned but **not digest-pinned** (no registry access when authored; digest-pin guidance is in each Dockerfile); GitHub Actions are pinned by major tag, not SHA.
