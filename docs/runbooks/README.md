# Runbooks and Troubleshooting Exercises

Operational guides for the OMS (order, inventory, fulfillment, notification services and the gateway). Each runbook doubles as a **practice exercise**: it says how to reproduce the failure in the local stack and which test guards against regression.

**Honesty notes (read first)**

* Every command in these documents is **illustrative: not executed against a live cluster.** The development machine has no Docker and no Kubernetes; no cluster exists for this project. Namespace `oms`, deployment names (`order-service`, ...), and the Prometheus job labels are conventions assumed from the contract; adjust to the real manifests in `infrastructure/kubernetes/`.
* All modules now exist on disk (checked 2026-10-18). Implemented and verified in code: `/api/v1/ops/messaging` and `/api/v1/ops/audit` (platform-common `OpsMessagingController`, `OpsAuditController`), the gateway aggregate `/api/v1/ops/health` (`OpsHealthController`), order-service `order/api/AdminController` (`GET /api/v1/admin/orders/failed`, `POST /api/v1/admin/orders/{id}/retry`, `GET /api/v1/admin/saga/{orderId}`, `POST /api/v1/admin/reconciliation/run`), and the metrics `oms.events.*`, `oms.outbox.pending`, `oms.saga.active`, `oms.saga.compensations`, `oms.saga.recovered`, `oms.saga.alerts`, `oms.order.processing.duration` (found in the main sources; dashboard/alert metric names matched a live scrape with 0 missing).
* `scripts/run-local.sh` and `scripts/stop-local.sh` exist and were run on 2026-10-18 (stack start takes about 2.5 minutes; it refuses to start if ports 19092/18080-18084 are taken). The stack start and the live flows are verified (`docs/validation/validation-report.md`); the failure-injection steps in each practice exercise are reproduction recipes, and only the fulfillment-down scenario (`tests/e2e/resilience-live.mjs`) and the FAILTOWN/insufficient-stock flows were actually run. Kubernetes commands remain unexecuted (no cluster).

## Index

| # | Runbook | Typical alert | Default severity |
|---|---|---|---|
| [01](01-orders-stuck-processing.md) | Orders stuck in processing | `oms.saga.active` high, stuck saga | SEV2 |
| [02](02-kafka-consumer-lag.md) | Kafka consumer lag | lag growing, `oms.outbox.pending` growing | SEV2 |
| [03](03-inventory-reservation-failures-under-concurrency.md) | Reservation failures under concurrency | spike of `InventoryReservationFailed`, oversell suspicion | SEV1 if oversell, else SEV3 |
| [04](04-database-latency-spikes.md) | Database latency spikes | `hikaricp.connections.pending` > 0, p99 up | SEV2 |
| [05](05-duplicate-order-events.md) | Duplicate order events | `oms.events.duplicates` spike, duplicate emails | SEV3 |
| [06](06-service-unavailable.md) | Service unavailable (503/readiness DOWN) | readiness failing, 5xx | SEV1/SEV2 |
| [07](07-failed-deployment.md) | Failed deployment | rollout stuck, crash loop | SEV2 |
| [08](08-jvm-memory.md) | JVM memory / OOM | heap > 90%, OOMKilled, GC pauses | SEV2 |

## Severity guide

| Level | Definition | Examples here | Response | Update cadence |
|---|---|---|---|---|
| SEV1 | Customer-facing outage or data-integrity loss | all orders failing; stock oversold; gateway down | page immediately, incident commander, 24x7 | every 15 min |
| SEV2 | Major degradation, workaround exists or limited scope | one service down with saga retrying; lag > 5 min; DB latency | page in hours of coverage, start within 15 min | every 30 min |
| SEV3 | Minor impact, no data loss | duplicate emails, single DLT record | ticket, next business day | daily |
| SEV4 | Cosmetic / no impact | dashboard glitch | backlog | none |

Escalate one level if: customer data exposure is suspected (also notify security), the issue is not understood after 30 minutes, or two services are affected.

## On-call basics

1. **Acknowledge** the page; state who has it.
2. **Assess impact** before diagnosing: which services, since when, how many orders (`oms.saga.active`, `http.server.requests` 5xx ratio).
3. **Stabilise before fixing:** roll back the last deploy, scale, or pause the traffic source; do not hot-fix in production.
4. **Collect evidence before restarting:** thread dump, heap dump if memory, `ops/messaging` output, one correlationId trace. A restart destroys evidence.
5. **Communicate:** one channel, timestamps, hypotheses and actions. Never paste tokens, passwords, or customer emails into the channel; use ids.
6. **Do not edit data by hand** if an admin endpoint exists (retry, reconciliation); those write audit rows (`/api/v1/ops/audit`).
7. **Close with a postmortem** (blameless): timeline, root cause, what detected it, what should have, action items with owners and dates.

Access model: OPERATOR and ADMIN roles only. DLT inspection and reconciliation are ADMIN. Obtain tokens from the identity provider or, in dev profile only, `POST /api/v1/auth/token`; the examples below write `<JWT>`.

## Command cheat sheet (all illustrative: not executed against a live cluster)

```bash
# Kubernetes (namespace oms assumed)
kubectl -n oms get pods -o wide
kubectl -n oms describe pod <pod>
kubectl -n oms logs deploy/order-service --since=15m | grep <correlationId>
kubectl -n oms logs <pod> --previous                       # after a crash
kubectl -n oms top pod
kubectl -n oms rollout status deploy/order-service
kubectl -n oms rollout history deploy/order-service
kubectl -n oms rollout undo deploy/order-service
kubectl -n oms port-forward svc/order-service 18081:18081

# Health and metrics (ports from the contract; locally on localhost)
curl -s localhost:18081/actuator/health/readiness
curl -s localhost:18081/actuator/health/liveness
curl -s localhost:18081/actuator/prometheus | grep '^oms_'

# Operational endpoints (OPERATOR/ADMIN token)
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/order/messaging | jq
curl -s -H "Authorization: Bearer <JWT>" "localhost:18080/api/v1/ops/order/audit?entityType=Order&entityId=<orderId>" | jq
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/admin/saga/<orderId> | jq
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/health | jq

# Kafka
kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group order-service
kafka-topics.sh --bootstrap-server localhost:19092 --list
kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic oms.inventory.events.v1.DLT \
  --from-beginning --max-messages 5 --property print.key=true --property print.headers=true

# PostgreSQL
psql -h <host> -U <ops_user> -d order_db -c "select pid,state,wait_event_type,now()-query_start as age,left(query,80) from pg_stat_activity where state<>'idle' order by age desc;"

# JVM
jcmd <pid> VM.flags ; jcmd <pid> Thread.print ; jstat -gcutil <pid> 1000 5
jcmd <pid> GC.heap_dump /tmp/heap.hprof      # contains data: handle as confidential, delete after use
```

Ports locally: gateway 18080, order 18081, inventory 18082, fulfillment 18083, notification 18084, Kafka 19092.

## Metrics referenced (contract section 7 plus Micrometer defaults)

`oms.events.processed`, `oms.events.duplicates`, `oms.events.retries`, `oms.events.dead_lettered`, `oms.events.ignored` (all verified in `MessagingMetrics`), `oms.outbox.pending`, `oms.order.processing.duration`, `oms.saga.active`, `oms.saga.compensations`, `jvm.memory.used`, `jvm.gc.pause`, `hikaricp.connections.active|pending|max`, `http.server.requests`, Kafka consumer metrics / lag. In Prometheus, dots become underscores and counters get `_total` (for example `oms_events_dead_lettered_total`, `oms_outbox_pending`).

## Related

* Review scenarios that produce these incidents: [../code-review/README.md](../code-review/README.md)
* Debugging method: [../mentoring/debugging.md](../mentoring/debugging.md)
* Shared design: [../architecture/00-shared-contract.md](../architecture/00-shared-contract.md)
