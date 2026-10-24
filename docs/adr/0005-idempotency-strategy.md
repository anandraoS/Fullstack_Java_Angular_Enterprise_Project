# ADR-0005: Idempotency at the API, the consumer and the state machine

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

Duplicates arrive from three places: clients retrying `POST /orders` after a timeout, Kafka redelivering after a rebalance or failed offset commit (at-least-once), and the saga recovery job deliberately re-sending commands. Each must be harmless.

## Decision drivers

* A retried order submission must never create a second order.
* A redelivered event must never apply its business effect twice.
* Idempotency state must commit atomically with the effect it protects.
* Must work without Redis (Redis is optional and off by default).

## Considered options

1. Rely on Kafka exactly-once semantics (transactions) only.
2. Three layers: API `Idempotency-Key`, consumer `processed_events`, state-machine idempotency (chosen).
3. Natural-key uniqueness only (for example unique order number).

## Decision outcome

Three complementary layers.

1. API: `Idempotency-Key` header is mandatory on `POST /api/v1/orders` (8-64 chars `[A-Za-z0-9_-]`). Table `idempotency_keys` (order-service) stores key, request body hash, response status/body, and the order id. Same key and same body returns 202 with the original body and `Idempotent-Replay: true`; same key and different body returns 422 `IDEMPOTENCY_KEY_REUSED`; missing key returns 400 `IDEMPOTENCY_KEY_REQUIRED`. The unique constraint on the key makes concurrent first-requests race safely: the loser fails the insert and re-reads the winner's result. The key insert and the order insert share one transaction. Exceptions: `IdempotencyKeyRequiredException`, `IdempotencyKeyReusedException` in `services/platform-common/.../common/error`.
2. Consumer: `processed_events(consumer, event_id)` with a unique constraint, inserted in the same transaction as the business change by `IdempotentEventProcessor` (`services/platform-common/.../common/idempotency`). A duplicate violates the constraint, the transaction is skipped, `oms.events.duplicates` increments. Notification adds a second guard: unique (`source_event_id`, `template`).
3. State machine: `OrderStateMachine` treats an already-applied transition as a no-op, and reservations are unique per `order_id`, so a duplicate `ReserveInventory` returns the original outcome. This covers cases where the same logical command is re-issued with a new `eventId` (saga recovery re-sends).

Together these give an effectively-once business effect on top of at-least-once delivery. This is not Kafka exactly-once; see `docs/architecture/delivery-semantics.md` for the honest limits.

### Consequences

* Good: safe client retries, safe redelivery, safe recovery job; no external store required; failure windows are covered by database atomicity.
* Bad: `idempotency_keys` and `processed_events` grow and need retention jobs (delete older than the maximum redelivery horizon); a key reused after expiry would be treated as new; one extra insert per consumed event; clients must generate good keys (UUIDs recommended) and the UI does so per submission attempt.

## Alternatives rejected

* Kafka transactions only: cover Kafka-to-Kafka read-process-write, not the database effect or HTTP clients; does not protect `POST /orders`.
* Natural keys only: there is no business-provided unique order number at submission time, and it would not detect same-key-different-body misuse.

## Links

* Contract sections 5, 6, 7; frontend `frontend/oms-ui/src/app/features/orders/order-create.ts`
* ADR-0006, ADR-0009; `docs/architecture/delivery-semantics.md`
