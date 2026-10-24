# Runbook 07 - Failed deployment

All commands: illustrative, not executed against a live cluster. Manifests are expected under `infrastructure/kubernetes/` (Kustomize base + overlays), CI in `.github/workflows/ci.yml`. At the time of writing these have only been **statically validated** (`scripts/validate-infra.sh`), never applied to a cluster.

## Symptoms

* `kubectl rollout status` hangs or reports `deployment exceeded its progress deadline`.
* New pods `CrashLoopBackOff`, `ImagePullBackOff`, `CreateContainerConfigError`, or `Running` but never Ready.
* Error rate or latency rises right after a release; consumer lag grows after the rollout.
* Flyway failure in startup logs (`Migration checksum mismatch`, `Detected resolved migration not applied`, `Validate failed`).

## Metrics and PromQL

```
kube_deployment_status_replicas_unavailable{namespace="oms"} > 0
kube_deployment_status_observed_generation != kube_deployment_metadata_generation
sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
changes(kube_pod_container_status_restarts_total{namespace="oms"}[10m])
sum by (topic) (rate(oms_events_processed_total[5m]))
increase(oms_events_dead_lettered_total[10m])
```

Compare the minutes after the rollout to the baseline before it.

## Diagnostics

```bash
kubectl -n oms rollout status deploy/order-service --timeout=120s
kubectl -n oms rollout history deploy/order-service
kubectl -n oms get rs -l app=order-service
kubectl -n oms describe pod <new-pod> | sed -n '/Events/,$p'
kubectl -n oms logs <new-pod> | head -80                   # startup errors: config, secret, Flyway
kubectl -n oms logs <new-pod> --previous | tail -80
kubectl -n oms get deploy order-service -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
kubectl -n oms get secret oms-jwt -o jsonpath='{.metadata.name}{"\n"}'     # existence only; never print secret values
kubectl -n oms exec deploy/order-service -- env | grep -c OMS_JWT_SECRET    # count only, do not display
curl -s localhost:18081/actuator/health/readiness | jq
```

Database migration state (order_db):

```sql
select installed_rank, version, description, success, installed_on from flyway_schema_history order by installed_rank desc limit 5;
```

## Root-cause tree

```
Deployment failed
|- Image: tag missing, registry credentials, architecture mismatch -> ImagePullBackOff
|- Config: missing env/secret (OMS_JWT_SECRET absent or < 32 bytes -> intentional fail-fast), wrong datasource URL, bad Kafka bootstrap
|- Flyway
|    |- failed migration (success = false): needs manual repair decision with DBA
|    |- checksum mismatch: someone edited an applied migration (never edit; add a new version)
|    |- version ordering: platform-common V1..V8, service V10+, seed V1000+ (dev only); a new migration below the max applied version fails validation
|    '- long migration holds locks -> readiness probe timeout -> runbook 04
|- Probes: initialDelay too short for migrations -> restarts; use a startup probe
|- Resources: requests exceed quota/node capacity (Pending), limits too low (OOMKilled -> runbook 08)
|- Behavioural regression passing probes: higher 5xx/latency, DLT growth, schema/event incompatibility
|    '- incompatible event/API change: v1 changes must be additive, consumers tolerant (contract section 5 and 7)
'- Mixed versions during rollout (old + new pods): contract-breaking change not backward compatible
```

## Mitigation

1. **Roll back first, investigate second:**

```bash
kubectl -n oms rollout undo deploy/order-service
kubectl -n oms rollout status deploy/order-service
```

2. If a migration already ran and the old version cannot work with the new schema, rollback of the pod is not enough: that is why migrations must be backward compatible (expand, then contract in a later release). Escalate to SEV1 and involve the DBA; do not manually edit `flyway_schema_history` without sign-off.
3. A failed Flyway migration: `flyway repair` only after fixing the cause and with approval; prefer a corrective new migration.
4. Behavioural regression: roll back; replay is automatic because the outbox/Kafka buffered events and consumers are idempotent. Check DLTs for records produced by the bad version.
5. Record the failed version, timeline and decision in the incident ticket.

## Prevention

* CI gates: unit + embedded Kafka tests, Flyway migrate against H2 and (where Docker exists) PostgreSQL, OpenAPI diff, event schema check.
* Rolling update with `maxUnavailable: 0`, readiness gating, `progressDeadlineSeconds`, canary where possible.
* Expand/contract migrations; never edit applied migrations; migration review checklist (lock level, table rewrite, index `CONCURRENTLY`).
* Config validation at startup (fail-fast) and in CI manifests lint.
* Post-deploy smoke test: `scripts/smoke-test.sh` / `smoke-test.mjs` exist and are meant for the local stack; adapt the base URL for an environment.

## Practice exercise

1. Local only (no cluster): in a scratch copy, add a migration `V10__x.sql` to a service with a deliberate SQL error, start the service via the local scripts, observe startup failure and the Flyway message. Then edit an already-applied migration file and restart against the same H2 file DB to see a checksum mismatch (in-memory H2 resets on restart; use a file-based URL for this exercise).
2. Start a service with no `OMS_JWT_SECRET` and observe fail-fast.
3. If a cluster is available later: apply an overlay with a non-existent image tag, observe `ImagePullBackOff`, and practise `rollout undo`.

## Regression test pointers

* Flyway migration test on H2 (`MODE=PostgreSQL`) and PostgreSQL Testcontainers twin (skipped without Docker); migration version-range convention test (open: not written). Real migration tests on H2: `SeedMigrationTest` (order-service), `SeedDataTest` (inventory); the PostgreSQL twins (`*PostgresIT`) were never executed.
* `scripts/validate-infra.sh` for static manifest validation (exists).
* Backward-compat test: consumer ignores unknown fields and unknown event types (`EventEnvelopeTest` and router tests in platform-common, exist).
