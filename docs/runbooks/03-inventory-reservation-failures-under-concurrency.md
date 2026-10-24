# Runbook 03 - Inventory reservation failures under concurrency

All commands: illustrative, not executed against a live cluster. inventory-service is implemented (`inventory/service/ReservationService`, three strategies in `inventory/reservation/`, `inventory/web/InventoryController`); the concurrency evidence is H2 only, nothing was run on PostgreSQL.

## Symptoms

Two distinct problems share this runbook; tell them apart first.

A. **Legitimate rejections spike:** many orders REJECTED with `statusReason` "insufficient stock" for a hot SKU. Expected when demand exceeds stock; not an incident unless stock data is wrong.

B. **Integrity violation (SEV1):** `reserved > on_hand`, negative `available`, more RESERVED reservation quantity than stock, or fulfillment unable to ship confirmed orders.

Others: deadlock errors in logs (`deadlock detected`), `ObjectOptimisticLockingFailureException` storms on stock edits, reservation timeouts, `hikaricp.connections.pending` during bursts.

## Metrics and PromQL

```
sum(increase(oms_events_processed_total{type="InventoryReservationFailed"}[10m]))
sum(increase(oms_events_processed_total{type="InventoryReserved"}[10m]))
rate(oms_events_retries_total{topic="oms.inventory.commands.v1"}[5m])
max(hikaricp_connections_pending{service="inventory-service"})
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service="inventory-service"}[5m])))
```

## Diagnostics

```bash
# Stock and reservations (OPERATOR/ADMIN)
curl -s -H "Authorization: Bearer <JWT>" "localhost:18080/api/v1/inventory?sku=SKU-1019" | jq
curl -s -H "Authorization: Bearer <JWT>" "localhost:18080/api/v1/inventory/reservations?status=RESERVED&size=50" | jq
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/inventory/messaging | jq
```

Invariant checks in `inventory_db` (read-only):

```sql
-- Must return zero rows
select product_id, on_hand, reserved from stock where reserved > on_hand or reserved < 0 or on_hand < 0;

-- Reserved quantity per stock row vs sum of live reservation items (adapt table/column names to the real schema)
select s.product_id, s.reserved as stock_reserved, coalesce(sum(i.quantity),0) as live_items
  from stock s
  left join inventory_reservation_items i on i.product_id = s.product_id
  left join inventory_reservations r on r.id = i.reservation_id and r.status in ('RESERVED','COMMITTED')
 group by s.product_id, s.reserved
having s.reserved <> coalesce(sum(i.quantity),0);
```

Locks and waits (PostgreSQL):

```sql
select a.pid, a.state, a.wait_event_type, a.wait_event, now()-a.query_start as age, left(a.query,100) as query
  from pg_stat_activity a where a.datname = 'inventory_db' and a.state <> 'idle' order by age desc;

select l.pid, l.locktype, l.mode, l.granted, a.query
  from pg_locks l join pg_stat_activity a on a.pid = l.pid
 where not l.granted;

select pg_blocking_pids(pid) as blockers, pid, left(query,80) from pg_stat_activity where cardinality(pg_blocking_pids(pid)) > 0;
```

Deadlock evidence: PostgreSQL server log lines `deadlock detected` with the two statements and process ids; application log lines for the retried/redelivered command with the same correlationId.

## Root-cause tree

```
Reservation problem
|- Rejections spike, invariants hold (A)
|    |- genuine demand > stock -> business: restock via PUT /inventory/{id}/stock
|    |- reservations never released: expired/cancelled reservations not cleaned -> check EXPIRED scheduler, ReleaseInventory consumption, runbook 01
|    '- stock edit lowered on_hand (adjust rejected below reserved by design)
|- Invariants broken (B)
|    |- check-then-act code path (read, compare in Java, write) -> code-review scenario 01
|    |- direct SQL/admin script bypassing the conditional UPDATE
|    '- partial multi-line commit after failure (missing rollback) -> scenario 01
|- Deadlocks
|    '- lines locked/updated in non-sorted productId order, or FOR UPDATE variant mixed with the atomic one
|- Optimistic lock storms (stock admin edit)
|    '- hot row edited concurrently with reservations that bump version; retry logic missing or unbounded
'- Latency during bursts -> hot-row contention + long transactions (remote call inside tx) -> runbook 04
```

## Mitigation

* Case A: nothing to fix technically; confirm stock numbers with the business owner. Adjust stock with the audited endpoint, e.g. restock:

```bash
curl -s -X PUT -H "Authorization: Bearer <JWT>" -H "Content-Type: application/json" \
  -d '{"delta": 50, "reason": "restock PO-synthetic-1"}' localhost:18080/api/v1/inventory/a0000000-0000-0000-0000-000000001019/stock
```

* Case B (SEV1): stop the bleed by pausing order intake for the affected SKU (deactivate product, ADMIN `DELETE /api/v1/products/{id}`) or scaling `inventory-service` consumers to 0 while keeping the outbox intact; collect the invariant query output; open an incident. Repair via reconciliation and audited stock adjustments, not ad hoc SQL. Affected orders need business decisions (cancel the latest ones: cancel releases reservations through the saga).
* Deadlocks: PostgreSQL aborts one transaction; the Kafka retry handles it. Fix ordering in code.

## Prevention

* The single conditional UPDATE `... WHERE on_hand - reserved >= :q`, sorted productId order, all-or-nothing per reservation in one transaction (contract section 7).
* Unique `order_id` on reservations makes duplicate commands no-ops.
* CHECK constraint `reserved <= on_hand` where the portable SQL allows it, as a last line of defence.
* Nightly invariant query job with an alert on non-empty result.
* Concurrency test in CI (below).

## Practice exercise

1. Local stack up (`scripts/run-local.sh`; verified on 2026-10-18). Seed has SKU-1019 stock 3 and SKU-1020 stock 0.
2. Fire 20 parallel order creations for SKU-1019 quantity 1 (distinct Idempotency-Keys), for example with a loop of backgrounded `curl` calls or `performance/` k6.
3. Expect exactly 3 orders to reach CONFIRMED path and 17 REJECTED; check `reserved = 3`, `available = 0`.
4. Run the invariant SQL; expect zero rows.
5. Variation: swap in the check-then-act code from code-review scenario 01 on a scratch branch and repeat; observe `reserved` anomalies. Never do this on shared environments.

## Regression test pointers

* Contention test: `docs/code-review/01-oversell-race.md` (20 threads, stock 3, exactly 3 succeed); PostgreSQL twin extends `AbstractPostgresIT` (exists in `platform-test-support`, skipped without Docker).
* Opposing multi-line orders deadlock test (exists): `ReservationConcurrencyTest#oppositeItemOrdersDoNotDeadlock`, run for every strategy on H2; the live 30-order oversell run (`tests/e2e/oversell-live.mjs`: 3 FULFILLED, 27 REJECTED, never above 3 committed) passed twice.
* `PUT /inventory/{id}/stock` below reserved returns 409 `CONFLICT` (exists): `InventoryApiTest#adjustingBelowTheReservedQuantityIsRejectedWith409AndChangesNothing`.
