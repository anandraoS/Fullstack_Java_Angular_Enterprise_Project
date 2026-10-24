# Runbook 06 - Service unavailable (503 / readiness DOWN)

All commands: illustrative, not executed against a live cluster.

## Symptoms

* Clients get 503 `UPSTREAM_UNAVAILABLE` (gateway cannot reach a service, or order-service cannot reach inventory), 504 after the gateway's 10 s response timeout, or 502 from the ingress.
* `GET /api/v1/ops/health` shows a service `DOWN` (the endpoint always returns HTTP 200 and reports status in the body).
* Kubernetes: pods `NotReady`, `CrashLoopBackOff`, `OOMKilled`, or no endpoints behind the Service.
* Circuit breaker open on the order -> inventory call; sagas record `lastError` and retry.

## Metrics and PromQL

```
up{job=~"order-service|inventory-service|fulfillment-service|notification-service|api-gateway"} == 0
sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum by (service) (rate(http_server_requests_seconds_count[5m]))
kube_deployment_status_replicas_available{namespace="oms"} < kube_deployment_spec_replicas{namespace="oms"}
resilience4j_circuitbreaker_state{state="open"} == 1
changes(kube_pod_container_status_restarts_total{namespace="oms"}[15m])
```

## Diagnostics

```bash
# 1. Aggregate view
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/health | jq

# 2. Direct probes (bypass the gateway)
curl -s -o /dev/null -w '%{http_code}\n' localhost:18081/actuator/health/liveness
curl -s localhost:18081/actuator/health/readiness | jq
curl -s localhost:18081/actuator/health | jq '.components'      # db, kafka, diskSpace etc. if details are exposed on the management port

# 3. Kubernetes
kubectl -n oms get pods -o wide
kubectl -n oms describe pod <pod> | sed -n '/Events/,$p'
kubectl -n oms get endpoints order-service
kubectl -n oms logs <pod> --previous | tail -100
kubectl -n oms get events --sort-by=.lastTimestamp | tail -20

# 4. Dependencies from inside the cluster
kubectl -n oms exec deploy/order-service -- wget -qO- http://inventory-service:18082/actuator/health/readiness
```

Interpretation: liveness fails -> the container is restarted (deadlock, OOM, fatal startup error). Readiness fails while liveness passes -> a dependency is down (DB, Kafka) or the app is still starting; it is removed from the Service endpoints, which is what you want. A service restarting in a loop with `OMS_JWT_SECRET` missing or shorter than 32 bytes is **intended** fail-fast behaviour (contract); fix the secret, never relax the check.

## Root-cause tree

```
503 / DOWN
|- Gateway itself
|    |- pods down -> everything unavailable (SEV1)
|    '- route target wrong/timeout 10 s -> backend slow, not down
|- Single service pods NotReady
|    |- CrashLoopBackOff
|    |    |- startup failure: missing/short OMS_JWT_SECRET, bad datasource URL, Flyway migration failure (checksum/ordering), port clash
|    |    |- OOMKilled -> runbook 08
|    |    '- recent deploy -> runbook 07
|    |- Readiness fails, liveness OK
|    |    |- DB unreachable / credentials rotated -> check secret mount, DB health
|    |    '- Kafka admin check failing (should not fail readiness; if it does, config bug)
|    '- No pods scheduled: resource quota, node pressure, image pull error
|- Dependency-induced
|    |- inventory down -> order VALIDATE step retries (Resilience4j 3 attempts) then circuit opens; orders accumulate (runbook 01)
|    '- Postgres down -> all services DOWN; Kafka consumers pause; outbox buffers nothing new (no writes) 
'- Network: NetworkPolicy blocks management port / service DNS issue
```

## Mitigation

1. Identify the smallest failing component and its last change (`kubectl rollout history`).
2. Last deploy suspected: `kubectl -n oms rollout undo deploy/<name>` (runbook 07).
3. Dependency down: restore it; services recover on their own (readiness returns, breakers half-open, `SagaRecoveryJob` re-drives).
4. Capacity: `kubectl -n oms scale deploy/<name> --replicas=<n>` when pods are healthy but overloaded.
5. Gateway timeouts: check backend p99 and DB (runbook 04) before raising timeouts.
6. Customer-facing messaging while degraded: orders are accepted asynchronously (202) and processed when dependencies return; do not tell customers orders are lost.

## Prevention

* Readiness probe depends on what the service truly needs (DB), liveness only on the process; startup probe for slow Flyway migrations.
* PodDisruptionBudget and at least 2 replicas for gateway and order-service.
* Alert on `up == 0`, 5xx ratio, available replicas < desired, breaker open > 5 min.
* Fail-fast on bad config stays enabled; configuration validated in CI (`scripts/validate-infra.sh` exists; static validation only).
* Game days: periodically kill inventory-service and verify sagas recover.

## Practice exercise

1. Local stack (`scripts/run-local.sh`, verified on 2026-10-18; the kill-inventory step itself was not run, the fulfillment-down variant was: `tests/e2e/resilience-live.mjs`).
2. `curl localhost:18080/api/v1/ops/health` -> all UP.
3. Kill inventory-service (PID in `.run/`). Re-run: inventory `DOWN`, HTTP still 200. Place an order: it is accepted (202); check the saga/lastError and the order-service log for breaker and retry lines.
4. Restart inventory-service; confirm the order proceeds without intervention.
5. Start order-service with `OMS_JWT_SECRET` set to a 10 character value (synthetic, throwaway); expect startup failure with a clear message. Restore a generated 32+ byte secret.

## Regression test pointers

* `JwtSecret` fail-fast behaviour (platform-common security tests; verify in `services/platform-common/src/test`).
* Order-to-inventory adapter (exists): `InventoryClientResilienceTest` (11 tests: retry, read timeout, circuit opens and closes again, 4xx not retried).
* Gateway `ops/health` aggregation returns 200 with a DOWN body when a backend is down (exists): `OpsHealthTest#oneServiceDownIsDegradedAndStillHttp200`, `#allDownIsDown`, `OpsHealthUnreachableTest`; also `gateway/ResilienceTest`.
