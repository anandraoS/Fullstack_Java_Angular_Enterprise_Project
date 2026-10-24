# Runbook 01 - Orders stuck in processing

All commands: illustrative, not executed against a live cluster. The order-service saga/admin endpoints are implemented (`order/api/AdminController`: `/api/v1/admin/saga/{orderId}`, `/orders/{id}/retry`, `/orders/failed`, `/reconciliation/run`) and covered by `AdminApiTest`; recovery is `order/saga/SagaRecoveryJob` (`oms.saga.stuck-after-seconds` default 60, `oms.saga.max-attempts` default 5) and `ReconciliationService`.

## Symptoms

* Customers report orders that never leave CREATED / VALIDATED / INVENTORY_RESERVED / CONFIRMED.
* `oms.saga.active` rises and does not drain; `oms.order.processing.duration` has no new samples (it records CREATED to terminal).
* `GET /api/v1/admin/orders/failed` lists COMPENSATION_PENDING orders.
* No error rate in `http.server.requests`: the HTTP side is healthy, the asynchronous side is not.

## Metrics and PromQL

```
oms_saga_active                                           # gauge: rising without bound = stuck
rate(oms_order_processing_duration_seconds_count[5m])     # completions per second; 0 while orders arrive = stalled
increase(oms_saga_compensations_total[15m])
increase(oms_events_dead_lettered_total[15m])
oms_outbox_pending{service="order-service"}
sum by (topic) (rate(oms_events_processed_total[5m]))
```

## Diagnostics

```bash
# 1. Which step is stuck? (OPERATOR/ADMIN)
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/admin/saga/<orderId> | jq '{state,currentStep,attempts,lastError,steps}'

# 2. Order, history and audit
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/orders/<orderId>/history | jq
curl -s -H "Authorization: Bearer <JWT>" "localhost:18080/api/v1/ops/order/audit?entityType=Order&entityId=<orderId>" | jq

# 3. Messaging health of each hop
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/order/messaging     | jq '{consumerGroup,outboxPending,retries,deadLettered,topics}'
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/inventory/messaging | jq '{consumerGroup,outboxPending,retries,deadLettered,topics}'

# 4. Readiness of everything
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/health | jq

# 5. Pods and logs
kubectl -n oms get pods
kubectl -n oms logs deploy/order-service --since=30m | grep <correlationId>
kubectl -n oms logs deploy/inventory-service --since=30m | grep <correlationId>
```

Database (order_db), stuck sagas older than the threshold (default 60 s):

```sql
select order_id, state, current_step, attempts, last_error, updated_at
  from saga_instances
 where state not in ('COMPLETED','FAILED','CANCELLED','REJECTED')   -- adapt to the real saga state names
   and updated_at < now() - interval '2 minutes'
 order by updated_at;
select count(*) from outbox_events where published_at is null;
```

## Root-cause tree

```
Order stuck
|- saga currentStep = VALIDATE
|    |- inventory REST unreachable / breaker open -> attempts rising, lastError UPSTREAM_UNAVAILABLE -> runbook 06 (inventory down)
|    '- order-service thread/connection starvation -> runbook 04 / 08
|- currentStep = RESERVE_INVENTORY
|    |- order outbox pending high -> relay stuck or broker down -> runbook 02
|    |- inventory consumer lag high -> runbook 02
|    |- record in oms.inventory.commands.v1.DLT -> read x-exception-*; poison vs transient
|    '- reservation exists but InventoryReserved not consumed by order -> check oms.inventory.events.v1 lag and order DLT
|- currentStep = CONFIRM_ORDER / FULFILL
|    |- fulfillment down or FAILTOWN shipping city (deterministic failure) -> expected FulfillmentFailed -> COMPENSATION_PENDING
|    '- FulfillmentCompleted not consumed -> lag on oms.fulfillment.events.v1
'- state COMPENSATION_PENDING for long
     |- ReleaseInventory lost/failed -> SagaRecoveryJob should re-send; check attempts vs oms.saga.max-attempts
     '- retries exhausted -> flagged for manual action
```

## Mitigation

1. Fix the underlying dependency first (broker, inventory, DB). The `SagaRecoveryJob` re-drives stuck steps automatically using idempotent commands, once the dependency is back.
2. For individual orders, use the admin retry (idempotent, retries the current saga step, audited):

```bash
curl -s -X POST -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/admin/orders/<orderId>/retry
```

3. For inconsistencies (terminal order with reservation still RESERVED, CONFIRMED with no fulfillment progress), run reconciliation (ADMIN):

```bash
curl -s -X POST -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/admin/reconciliation/run | jq '{examined,repaired,details}'
```

4. Do not update `orders.status` by SQL: it skips history, audit, outbox. If unavoidable, SEV1 decision with two people and a recorded audit note.
5. Communicate customer impact using order ids only.

## Prevention

* Alert: `oms_saga_active` increasing for 10 min; stuck-saga count > 0 for 5 min; `oms_events_dead_lettered_total` increase > 0.
* Keep `oms.saga.stuck-after-seconds` and `max-attempts` tuned (defaults 60 and 5) and tested.
* Every step command and event handler idempotent (see `docs/mentoring/design-patterns.md`).
* Scheduled reconciliation with results audited.

## Practice exercise

Goal: make an order stick at RESERVE_INVENTORY, observe, then recover.

1. Start the local stack: `scripts/run-local.sh` (prints the generated demo password once; verified on 2026-10-18). Obtain a customer token from `POST /api/v1/auth/token` (dev profile).
2. Stop inventory-service only (kill its PID from `.run/`; `scripts/stop-local.sh` stops everything).
3. Create an order for SKU-1001 (see `docs/api/examples.md`). Expect 202 and status CREATED/VALIDATED depending on whether validation (REST) can succeed. With inventory down, validation retries then the saga records `lastError`.
4. Inspect `/api/v1/admin/saga/<orderId>` and `ops/order/messaging`; note `attempts` and `outboxPending`.
5. Restart inventory-service. Watch the order reach CONFIRMED then FULFILLED without manual action (recovery job).
6. Repeat, but set `oms.saga.max-attempts=1` and see FAILED / COMPENSATION_PENDING flagged; recover with admin retry.

Questions: Which log line proves the command was re-sent? Why does a duplicate command not double-reserve?

## Regression test pointers

* `OrderStateMachineTest` (13 tests, exists, order-service).
* `SagaRecoveryTest` (12 tests, exists): `aStuckCompensationGetsReleaseInventoryResentWithItsOriginalReason`, `theJobRedrivesOnlySagasThatMadeNoProgressForTheConfiguredTime`, `validatedSagaThatNeverProgressesFailsAndDefensivelyReleases`; `ReconciliationTest` (5); live: `tests/e2e/resilience-live.mjs` (order parked while fulfillment is down completes after restart).
* Consumer duplicate test pattern: `services/platform-common/src/test/java/com/enterprise/oms/common/messaging/ConsumerKafkaTest.java` (exists).
