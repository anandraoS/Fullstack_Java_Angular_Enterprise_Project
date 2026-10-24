# Feature spec: Order cancellation

Status: Approved (spec); implemented in order-service, inventory-service and notification-service (re-checked against the code 2026-10-18; PostgreSQL variants not run, see `docs/known-limitations.md`)
Owner: order-service     Date: 2026-10
Related: contract sections 4, 5.1, 6, 7, 8; ADR-0003 (saga), ADR-0005 (idempotency), ADR-0006 (concurrency);
runbook `docs/runbooks/01-orders-stuck-processing.md`; threat model (unauthorized order access, duplicate submission).
Walkthrough of the method: `../../guides/spec-driven-workflow.md`.

## 1. Problem and goal
Customers who ordered by mistake and operators handling exceptions need to stop an order before it ships, without
leaving reserved stock stranded. Success = a cancel request ends with the order `CANCELLED` and any reservation
`RELEASED`, with no oversold or leaked stock, within the saga recovery bounds.

## 2. Scope
In: `POST /api/v1/orders/{id}/cancel`; transitions from `CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED`; compensation
via `ReleaseInventory`; events `OrderCancelled`; notification; history/audit/saga log; UI action.
Out: refunds/payments (no payment domain), cancelling after `FULFILLED`, partial cancellation of lines, customer self-service
after shipment, bulk cancel.

## 3. Actors and permissions
| Actor | May | May not |
|---|---|---|
| CUSTOMER | cancel own order | cancel others' orders (answer 404), see others' state |
| OPERATOR / ADMIN | cancel any order, retry stuck compensation (`/admin/orders/{id}/retry`) | - |
| SERVICE | consume/publish events only | call the public cancel endpoint |

## 4. Functional requirements
* FR-1 Cancel body `{ "reason": "<1..255 chars>" }`; reason is stored in history and in the event, never logged raw.
* FR-2 Allowed from `CREATED, VALIDATED` -> `CANCELLED` immediately.
* FR-3 Allowed from `INVENTORY_RESERVED, CONFIRMED` -> `COMPENSATION_PENDING`; orchestrator sends `ReleaseInventory{reason:CANCELLATION}`.
* FR-4 On `InventoryReleased` with reason `CANCELLATION`: `COMPENSATION_PENDING -> CANCELLED`; publish `OrderCancelled`.
* FR-5 From any other state (including `CANCELLED`, `FULFILLED`, `REJECTED`, `FAILED`, `COMPENSATION_PENDING`) -> 409 `INVALID_STATE_TRANSITION`.
* FR-6 Each transition writes `order_status_history`, `saga_step_log`, `audit_events` and the outbox row in ONE transaction.
* FR-7 If a reservation arrives after an order was cancelled in `CREATED`/`VALIDATED`, the orchestrator releases it; the order stays `CANCELLED`.
* FR-8 Concurrent cancel requests: exactly one succeeds; the others get 409 (no duplicate `OrderCancelled`).
* FR-9 `OrderCancelled` consumers (inventory release, notification) are idempotent.
* FR-10 Response is 200 with the updated Order JSON.

## 5. Acceptance criteria (Given / When / Then)
* AC-1 Immediate cancel. Given order O in `CREATED` (or `VALIDATED`) owned by customer1, When customer1 POSTs cancel with a
  reason, Then 200, status `CANCELLED`, history has one new row `(CREATED -> CANCELLED, actor customer1)`, an
  `OrderCancelled` outbox row exists, and no `ReleaseInventory` is issued (nothing was reserved).
* AC-2 Compensated cancel. Given O in `INVENTORY_RESERVED` or `CONFIRMED`, When the owner cancels, Then status becomes
  `COMPENSATION_PENDING` and a `ReleaseInventory{reason:CANCELLATION}` command is in the outbox; And when
  `InventoryReleased{reason:CANCELLATION}` arrives, Then the order is `CANCELLED` and `OrderCancelled` is published.
* AC-3 Ownership. Given O owned by customer1, When customer2 POSTs cancel, Then 404 `NOT_FOUND`, O unchanged, no event.
  (Operator may cancel: 200.)
* AC-4 Authentication. Given no/invalid token, When cancel is called, Then 401 `UNAUTHENTICATED`.
* AC-5 Invalid state. Given O in `FULFILLED`, When cancel is called, Then 409 `INVALID_STATE_TRANSITION` and O unchanged. The same
  holds for an order already `CANCELLED` (cancelling twice is 409, by design of `OrderStateMachine`).
* AC-6 Validation. Given a blank or 256+ char reason, Then 400 `VALIDATION_FAILED` with an `errors[]` entry for `reason`.
* AC-7 Late reservation. Given O cancelled while `VALIDATED`, When `InventoryReserved` for O arrives afterwards, Then
  `ReleaseInventory` is issued and O remains `CANCELLED`; stock `reserved` returns to its prior value.
* AC-8 Concurrency. Given O in `CONFIRMED`, When two cancel requests race, Then exactly one returns 200 and the other 409;
  exactly one `ReleaseInventory` and one `OrderCancelled` are produced.
* AC-9 Duplicate event. Given `InventoryReleased` is delivered twice, Then the order ends `CANCELLED` once, one history row,
  `oms.events.duplicates` increments, stock released once.
* AC-10 Stuck compensation. Given `ReleaseInventory` is lost, When `oms.saga.stuck-after-seconds` elapses, Then
  the recovery job re-sends it (idempotent); after `oms.saga.max-attempts` the order stays `COMPENSATION_PENDING` and is
  flagged for manual retry via `POST /api/v1/admin/orders/{id}/retry`.
* AC-11 Inventory side. Given a reservation `RESERVED` or `COMMITTED` for O, When `ReleaseInventory{CANCELLATION}` is
  handled, Then reservation `RELEASED`, `reserved -= q`, `InventoryReleased` published; handling it again is a no-op.
* AC-12 Notification. Given `OrderCancelled`, Then one notification is recorded; duplicate event creates no second one
  (unique `source_event_id, template`).
* AC-13 Logging. Cancel path logs contain the order id and correlation id, no reason text, no full email.

## 6. Design
### API
`POST /api/v1/orders/{id}/cancel`, roles owner/OPERATOR/ADMIN. Request `{reason}`. Responses: 200 Order; 400
`VALIDATION_FAILED`; 401; 404 (missing or not owner); 409 `INVALID_STATE_TRANSITION`; 429.
### State changes (from `OrderStateMachine`, trigger `CANCEL_REQUESTED` and `RELEASED_AFTER_CANCELLATION`)
| From | Trigger | To |
|---|---|---|
| CREATED, VALIDATED | CANCEL_REQUESTED | CANCELLED |
| INVENTORY_RESERVED, CONFIRMED | CANCEL_REQUESTED | COMPENSATION_PENDING |
| COMPENSATION_PENDING | RELEASED_AFTER_CANCELLATION | CANCELLED (CANCELLED repeat = no-op) |
All other (state, CANCEL_REQUESTED) pairs: invalid -> 409.
### Sequence (compensated case)
1. Controller resolves `CurrentUser`, loads order with ownership check (404 otherwise).
2. `[TX-1]` lock/update order row with version check (`@Version`) -> state machine `decide` -> history + saga_step_log +
   audit + outbox(`ReleaseInventory` on `oms.inventory.commands.v1`, key orderId). Commit.
3. Relay publishes command (at-least-once). Inventory `[TX-2]` processes once via `processed_events`, releases reservation,
   writes outbox `InventoryReleased`.
4. Order-service consumes `InventoryReleased` `[TX-3]`: `RELEASED_AFTER_CANCELLATION` -> `CANCELLED`, history/audit, outbox `OrderCancelled`.
5. Notification and inventory (for `OrderCancelled` fallback release) consume `OrderCancelled` idempotently.
### Concurrency
Two cancels: both read the same version; the second UPDATE fails the version check or finds a non-cancellable state and maps to 409
(retry reads the new state). No lost update; no duplicate event because the outbox row is in the winning tx only.
### Failure and recovery
Command lost or consumer down: `SagaRecoveryJob` re-sends; reconciliation repairs CANCELLED-with-RESERVED. Not a distributed
transaction: customers may observe `COMPENSATION_PENDING` for seconds to minutes (UI text must say "cancelling").
### Data and events
No new tables. Events: `ReleaseInventory{orderId, reason}` (command), `InventoryReleased{orderId, reservationId, reason}`,
`OrderCancelled{orderId, customerId, customerEmail, reason}`; all schemaVersion 1.

## 7. Non-functional
p95 cancel API < 300 ms (synchronous part only; not measured yet). Compensation completes < 10 s normal, bounded by recovery
config in failure. Metrics: `oms.saga.compensations`, `oms.saga.active`, `oms.events.*`, `oms.order.processing.duration`.

## 8. Security considerations
BOLA on cancel (AC-3), spoofed events (`OrderCancelled` accepted only from the topic the producer ACL permits; consumers
treat payload as data), reason text is untrusted (length cap, no raw logging), rate limiting at gateway, replay safe via state machine.
Examples: `../../flawed-ai-examples/05-bola-customer-id.md`, `../../flawed-ai-examples/06-pii-and-jwt-logging.md`.

## 9. Test plan and traceability (verified against the code, 2026-10-18)
Method names are the real ones found under `services/*/src/test`; the full reactor ran green on 2026-10-18 (`docs/validation/backend-test-summary.md`).
| AC | Test (class#method) | Level | Status | Docker |
|---|---|---|---|---|
| AC-1, AC-2 (domain) | `OrderStateMachineTest#cancelBeforeReservationIsImmediate`, `#cancelAfterReservationNeedsCompensation`, `#cancellableStatesAreExactlyThoseOfTheContract` | unit | exists, passes | no |
| AC-5 (domain) | `OrderStateMachineTest#fulfilledOrderCannotBeCancelled`, `#statusTriggerMatrix`, `#requireThrowsConflictForInvalidTransitionsAndReturnsTargetOtherwise` | unit | exists, passes | no |
| AC-2 (final step), AC-9 (domain no-op) | `OrderStateMachineTest#requireOnAnAlreadyAppliedTriggerKeepsTheStatus`, `#terminalStatesNeverMove` | unit | exists, passes | no |
| AC-1, AC-3, AC-5, AC-6 (API) | `OrderApiTest#customerCancelsBeforeValidationWithImmediateCancelledAndEvent`, `#cancelWithoutABodyUsesADefaultReason`, `#cancellingTwiceIs409InvalidStateTransition`, `#cancellingAFulfilledOrderIs409`, `#cancellingAnotherCustomersOrderIs404AndChangesNothing`, `#reasonLongerThan500CharactersIsRejected`; `OrderSecurityTest` (401/403) | API (H2) | exists, passes | no |
| AC-2, AC-7 (saga unit) | `OrderSagaOrchestratorTest#cancelBeforeAReservationIsImmediate`, `#cancelAfterAReservationCompensates`, `#aReservationForACancelledOrderIsReleasedNotConfirmed`, `#releaseAfterCancellationCancelsTheOrder` | unit/integration (H2) | exists, passes | no |
| AC-2, AC-7 (saga + Kafka) | `OrderSagaKafkaIT#cancellingAConfirmedOrderReleasesTheInventoryThenCancels`, `#aReservationThatArrivesAfterTheCancelIsReleasedAndTheOrderStaysCancelled` | integration (H2 + embedded KRaft Kafka) | exists, passes | no |
| AC-10 (stuck compensation) | `SagaRecoveryTest#aStuckCompensationGetsReleaseInventoryResentWithItsOriginalReason`; admin retry in `AdminApiTest` | integration (H2) | exists, passes | no |
| AC-8 (concurrent cancel) | no dedicated parallel-cancel test; resolved by the state machine and `@Version`; only the CONFIRMED-stage cancel is exercised live in `scripts/live-flows.mjs` (see `docs/known-limitations.md`, item 10) | - | open | no |
| AC-9 (consumer idempotency, platform) | `IdempotentProcessorAndAuditTest` in `platform-common` | integration | exists, passes | no |
| AC-11 (inventory release) | `ReservationLifecycleTest#releaseGivesStockBackEmitsInventoryReleasedAndWritesTheLedger`, `#releaseIsIdempotentAndTheCommandIsAnsweredAgainButTheEventStaysSilent`, `#releasingACommittedReservationWorks` | integration (H2) | exists, passes | no |
| AC-12 (notification) | `NotificationKafkaTest` (publishes `OrderCancelled`), `NotificationUnitTest` (`NotificationDraft.of(OrderCancelled)`), duplicate handling in `NotificationServiceTest` | integration/unit | exists, passes | no |
| AC-13 | `LogMaskingTest` (helper); `OrderTransitionsLoggingTest` | unit | partial | no |
| End-to-end through UI | `tests/e2e/specs/order-lifecycle.spec.ts` (cancel test): mock mode 18/18 and live mode against the real stack 18/18 on 2026-10-18 | e2e | exists, passes | no |
BDD-style scenarios (`cancelBeforeValidation`, `cancelConfirmedOrder`, `cannotCancelFulfilled`) are in `OrderBddScenariosTest` and `tests/bdd/order-scenarios.feature`.
The earlier planned names (`OrderCancellationApiTest`, `OrderCancellationSagaTest`, `ReleaseInventoryHandlerTest`, `OrderCancelledNotificationTest`) were never created; the classes above replaced them.

## 10. Rollout
No migration. Deploy order: inventory (understands `ReleaseInventory` already) -> order -> UI. Backward compatible
(additive). Rollback: previous image; in-flight `COMPENSATION_PENDING` orders continue via recovery job.

## 11. Open questions
1. Should customers be allowed to cancel `CONFIRMED` orders after fulfillment started (`PICKING`)? Contract says yes until `FULFILLED`; product owner to confirm.
2. Should cancel reason be PII-reviewed (free text)? Default: store, never log.

## 12. Verification record
Re-checked 2026-10-18: test names verified by grep against `services/*/src/test`; the full reactor run (968 cases, 939 executed, 0 failures, 29 skipped for Docker/benchmark) and the live cancel flows (`scripts/live-flows.mjs`: immediate cancel, cancel at CONFIRMED with reservation RELEASED, 409 on terminal, operator cancel) are recorded in `docs/validation/validation-report.md`. Still open: AC-8 has no dedicated parallel-cancel test (`docs/known-limitations.md`).
