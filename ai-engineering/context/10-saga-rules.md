# Saga rules

Load when: changing order state, orchestrator steps, recovery, or reconciliation. Normative: contract sections 6 and 8;
docs `docs/adr/0003`, `docs/architecture/saga-and-consistency.md`.

1. Orchestration: `OrderSagaOrchestrator` in order-service owns the flow `VALIDATE -> RESERVE_INVENTORY -> CONFIRM_ORDER
   -> FULFILL`; compensation `RELEASE_INVENTORY`. Participants never call each other to coordinate.
2. Every transition goes through the pure `OrderStateMachine` and, in ONE transaction, writes `order_status_history`,
   `saga_step_log`, `audit_events` and (if applicable) an outbox row.
3. Transitions are idempotent: re-applying an applied event is a no-op, not an error. Invalid pairs raise
   `InvalidStateTransitionException` (409 over the API).
4. Commands are idempotent at the receiver (unique `order_id` on reservations; `processed_events`).
5. Compensation is semantic, not a rollback: cancel after reservation issues `ReleaseInventory` and moves the order
   `COMPENSATION_PENDING -> CANCELLED` only on `InventoryReleased` with reason `CANCELLATION`.
6. Cancel racing a reservation: if the order is `CREATED`/`VALIDATED` and cancelled, a reservation that arrives later is
   released by the orchestrator and the order stays `CANCELLED`.
7. Recovery: `SagaRecoveryJob` re-sends the idempotent command after `oms.saga.stuck-after-seconds` (default 60); after
   `oms.saga.max-attempts` (default 5) non-compensable steps go `FAILED`, compensable ones stay `COMPENSATION_PENDING`
   and raise an alert metric (`oms.saga.compensations`, `oms.saga.active`).
8. Reconciliation (`ReconciliationService`) finds terminal-order-with-live-reservation, CONFIRMED-without-fulfillment
   progress, CANCELLED-with-RESERVED, repairs by compensating commands and audits what it did.
9. Never describe the saga as a distributed transaction. Users see intermediate states (`COMPENSATION_PENDING`); the UI
   and docs must say so.
10. Adding a step requires: state + trigger additions, matrix tests, a compensation (or an explicit "non-compensable"
    statement), recovery behaviour, a runbook note, and an ADR update.
