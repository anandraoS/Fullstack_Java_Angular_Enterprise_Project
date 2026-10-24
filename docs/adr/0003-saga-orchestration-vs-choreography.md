# ADR-0003: Orchestrated saga for order processing

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

An order spans validation, inventory reservation, confirmation, fulfillment and, on failure, compensation (release inventory). There is no distributed ACID transaction (ADR-0004). We need a coordination style whose state is visible, recoverable and testable.

## Decision drivers

* Operators must answer "where is order X stuck and why" with one query.
* Compensation paths (cancel, fulfillment failure, reservation expiry) must be explicit and exhaustively tested.
* Recovery of lost messages must be possible without guesswork.
* Keep services decoupled: inventory and fulfillment should not know about each other's workflows.

## Considered options

1. Choreography: each service reacts to events and emits new ones, no central owner.
2. Orchestration: order-service owns a saga instance and sends commands (chosen).
3. Workflow engine (Temporal, Camunda, Step Functions).

## Decision outcome

Orchestration inside order-service. `OrderSagaOrchestrator` drives `VALIDATE -> RESERVE_INVENTORY -> CONFIRM_ORDER -> FULFILL` with compensation `RELEASE_INVENTORY`. State is persisted in `saga_instances` and `saga_step_log`; the order lifecycle is the pure `OrderStateMachine` (contract section 6) so every transition is unit-testable and idempotent. Commands go out on `oms.inventory.commands.v1` and `oms.fulfillment.commands.v1`; participants reply with events on their own topics. A scheduled `SagaRecoveryJob` re-sends the idempotent command for steps stuck beyond `oms.saga.stuck-after-seconds`; after `oms.saga.max-attempts` the saga is FAILED (non-compensable) or flagged COMPENSATION_PENDING (compensable), and `ReconciliationService` repairs residual inconsistencies.

Choreography is still used where there is no coordination need: notification-service just consumes `oms.order.events.v1` and `oms.fulfillment.events.v1`; inventory consumes `OrderConfirmed`/`OrderCancelled` as a safety net.

### Consequences

* Good: one table shows the whole flow; compensation is a first-class, tested path; participants stay simple and idempotent; recovery has a single owner.
* Bad: order-service is a coordination hot spot and a central point of knowledge about the flow; it must be highly available; command/reply topology adds topics; the orchestrator itself must be idempotent and crash-safe (state and outbox written in one transaction, ADR-0009).

## Alternatives rejected

* Choreography: with four services and three compensation triggers, the flow becomes implicit across consumers; cyclic event dependencies and "who releases the stock?" ambiguity appear. Fine for 2-3 steps, poor for observability here.
* Workflow engine: the right answer for long, complex flows in production, but adds a heavy runtime that cannot run in the Docker-less local stack and hides the pattern this project exists to teach.

## Links

* Contract sections 6 and 8; `docs/architecture/saga-and-consistency.md`
* order-service package `com.enterprise.oms.order` (service module under `services/order-service`)
* ADR-0005, ADR-0009
