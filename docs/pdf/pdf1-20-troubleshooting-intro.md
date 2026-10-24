## How to use this section

Each scenario is a realistic production symptom. Start from the symptom, form a hypothesis from metrics, confirm with one cheap query, then act. The full runbooks follow in this section; every command in them is illustrative and was not run against a live cluster (stated in the runbook index).

## Symptom to first action

| Symptom | First hypothesis | First check | Runbook |
|---|---|---|---|
| Orders stay in `VALIDATED` for minutes | Reservation reply lost or consumer down | `GET /api/v1/admin/saga/{orderId}`, consumer lag, `SagaRecoveryJob` logs | 01 |
| Consumer lag keeps growing | Slow handler, poison pill, rebalance loop, DB pool exhausted | `GET /api/v1/ops/messaging`, `kafka-consumer-groups --describe`, Hikari pending | 02 |
| `InventoryReservationFailed` spike or negative availability | Hot product, strategy change, or a bug that bypasses the atomic update | Compare `reserved` and `on_hand`, reservation metrics | 03 |
| p99 latency up on every endpoint | DB lock contention, slow query, pool exhaustion | `hikaricp.connections.pending`, `pg_stat_activity` | 04 |
| Customers get two emails | Redelivery without effective dedupe, or unique key missing | `oms.events.duplicates`, `notifications` unique key | 05 |
| 503 from the gateway | Circuit open or readiness failing | `GET /api/v1/ops/health`, pod events | 06 |
| Rollout stuck or crash loop | Config, secret, migration or probe failure | `kubectl rollout status`, previous logs | 07 |
| Pod OOMKilled or long GC pauses | Heap sizing, leak, unbounded query | heap flags, `jcmd`, metrics | 08 |

## A repeatable investigation method

1. State the impact in one sentence and the start time.
2. Check what changed (deploy, config, traffic, dependency).
3. Use the RED view (rate, errors, duration) per service, then USE (utilisation, saturation, errors) for the database pool, CPU and consumer lag.
4. Follow one request: take the `X-Correlation-Id` from the response, search logs for it, open the trace.
5. Stabilise first (scale, roll back, pause a consumer), then fix, then write the review.

## Debugging exercises included in the repository

Seven code-review exercises (`docs/code-review/`) and two database performance write-ups (`docs/database/`) double as troubleshooting practice: oversell race, BOLA via body `customerId`, transactional self-invocation, Kafka publish inside a transaction, acknowledging offsets on exception, unbounded pagination with N+1 queries, PII in logs, plus the inventory EXPLAIN ANALYZE study and the slow-query, lock-contention and pool-exhaustion investigation.
