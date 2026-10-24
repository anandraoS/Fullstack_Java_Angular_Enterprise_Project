# Saga and consistency

Decision records: [ADR-0003](../adr/0003-saga-orchestration-vs-choreography.md), [ADR-0004](../adr/0004-database-per-service.md), [ADR-0009](../adr/0009-transactional-outbox.md). Normative flow: [contract sections 6 and 8](00-shared-contract.md).

## 1. What a saga is

A saga replaces one distributed transaction by a sequence of local transactions, each with a compensating action that semantically undoes it. There is no global rollback and no isolation across steps: intermediate states are visible, which is why orders have visible statuses and reservations have TTLs. Compensation is not "undo": a released reservation leaves a trace (`RELEASED`, `stock_movements`, audit rows).

## 2. Happy path (orchestrated by order-service)

| Step | Who | Action | Order status after |
|---|---|---|---|
| (accept) | order-service | `POST /orders` with Idempotency-Key; write order, saga instance, outbox `OrderCreated` in one tx; reply 202 | CREATED |
| VALIDATE | order-service to inventory (sync REST, circuit breaker + retry, SERVICE JWT) | validate products, active flag, prices | VALIDATED (or REJECTED) |
| RESERVE_INVENTORY | command `ReserveInventory` on `oms.inventory.commands.v1`; inventory reserves atomically (conditional UPDATE, sorted ids), emits `InventoryReserved` or `InventoryReservationFailed` | | INVENTORY_RESERVED (or REJECTED) |
| CONFIRM_ORDER | order-service on `InventoryReserved` | publish `OrderConfirmed` and command `StartFulfillment`; inventory commits the reservation (TTL cleared) | CONFIRMED |
| FULFILL | fulfillment-service simulates picking, packing, shipping | emits `FulfillmentStarted`, then `FulfillmentCompleted` (stock shipped) | FULFILLED, event `OrderFulfilled` |

Classes: `OrderSagaOrchestrator`, `OrderStateMachine`, `SagaRecoveryJob`, `ReconciliationService` in order-service (`services/order-service`, package `com.enterprise.oms.order`). Each transition writes `order_status_history`, `saga_step_log`, `audit_events` and any outbox event in one transaction, and applying the same event again is a no-op.

## 3. Compensation paths

| Trigger | Sequence | End state |
|---|---|---|
| Insufficient stock | `InventoryReservationFailed` | REJECTED (nothing to undo; reservation transaction rolled back) |
| Customer or operator cancel from CREATED/VALIDATED | cancel | CANCELLED; if a reservation arrives later the orchestrator issues `ReleaseInventory` and the order stays CANCELLED |
| Cancel from INVENTORY_RESERVED/CONFIRMED | COMPENSATION_PENDING, command `ReleaseInventory{CANCELLATION}`, then `InventoryReleased` | CANCELLED |
| Fulfillment fails after its own retries (for example shipping city `FAILTOWN`) | `FulfillmentFailed`, COMPENSATION_PENDING, `ReleaseInventory{FULFILLMENT_FAILURE}`, `InventoryReleased` | FAILED |
| Reservation expires (TTL, default 900 s) | inventory scheduler emits `InventoryReleased{EXPIRED}` | order in VALIDATED/INVENTORY_RESERVED moves to FAILED |
| Step never answers | `SagaRecoveryJob` re-sends the idempotent command after `oms.saga.stuck-after-seconds`; after `oms.saga.max-attempts` | FAILED if not compensable; COMPENSATION_PENDING flagged plus alert if compensable; operators use `POST /api/v1/admin/orders/{id}/retry` |

## 4. Eventual consistency

* Between commits of different services the system is inconsistent by design: an order may be CONFIRMED while stock is already committed, or INVENTORY_RESERVED briefly after the customer clicked cancel. All such windows are bounded by retries, TTLs and recovery.
* Invariants that hold at all times inside a service (never oversell, one reservation per order, valid state transitions) are enforced by local transactions and constraints.
* Cross-service invariants hold eventually, and `ReconciliationService` checks them: order terminal but reservation not released; CONFIRMED order with no fulfillment progress; CANCELLED order with a RESERVED reservation. It repairs by issuing compensating commands, writes audit events, runs on a schedule and via `POST /api/v1/admin/reconciliation/run` (ADMIN) returning `{examined, repaired, details[]}`.

## 5. Trade-offs

| Aspect | Gain | Cost |
|---|---|---|
| Local transactions only | Availability, short locks | No isolation across steps, visible intermediate states |
| Orchestration | Visible, recoverable flow | Orchestrator is a coordination point |
| Async commands over Kafka | Decoupling, backlog tolerance | Latency, duplicates, need for idempotency |
| Semantic compensation | Works across services | Not all actions are reversible (a sent email stays sent); notification is deliberately outside the compensable path |
| Reconciliation | Safety net for bugs and lost messages | Another job to run, monitor and keep correct |

## 6. What users see

* `POST /orders` returns 202 and status `CREATED`, not "done". The Angular order detail screen polls with growing delay (`pollUntil`, `pollIntervalMs` 1500 up to `pollMaxIntervalMs` 8000) until a terminal status, shows a timeline from `GET /orders/{id}/history`, and the status reason on REJECTED or FAILED.
* Insufficient stock appears seconds later as REJECTED with a reason, not as a synchronous error.
* Cancel on a CONFIRMED order moves through COMPENSATION_PENDING before CANCELLED; the UI shows the intermediate status honestly.
* Operators see stuck or failed orders in the failed-orders screen (`GET /api/v1/admin/orders/failed`), the saga step log (`GET /api/v1/admin/saga/{orderId}`) and can retry.
* Customers get notification emails (simulated) driven by events; a failed notification never changes order state.
* Honest scope: the flow is exercised by unit and embedded-Kafka tests and by the local stack on H2; multi-instance, real-PostgreSQL and broker-failure behaviour has not been run on the dev machine because Docker is unavailable there.
