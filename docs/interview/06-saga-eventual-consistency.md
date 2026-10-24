# Saga, Compensation and Eventual Consistency: Orchestration, Recovery and Reconciliation

An order in this platform crosses four databases, so it cannot be one ACID transaction. It is a **saga**: a sequence of local transactions, each with a compensating action, coordinated by an orchestrator inside `order-service`. This chapter walks through ADR-0003 and `docs/architecture/saga-and-consistency.md` and ties every claim to the code in `services/order-service/src/main/java/com/enterprise/oms/order/` (`saga`, `domain`, `service`, `entity`).

## Big picture and kid-friendly analogy

Imagine you are planning a birthday party and you have a **party planner** with a clipboard.

1. Book the cake. 2. Book the bouncy castle. 3. Send the invitations. 4. Hold the party.

Each booking is done by a different shop, and each shop has its own order book. There is no single magic button that books everything at once or cancels everything at once. So the planner keeps a **clipboard** for each party: which step is done, which is waiting, how many times she has phoned a shop.

* If the castle shop says "sorry, fully booked", the planner calls the cake shop and cancels the cake. That is **compensation**.
* But you cannot "un-send" an invitation. You can only send a new note, "party cancelled". That is a **semantic** compensation: it does not erase history, it adds a new fact that cancels the effect.
* If a shop does not answer, the planner **calls again after a while**, but she says exactly the same thing ("cake for Saturday, order number 17"), so the shop does not bake two cakes (idempotent commands).
* A second person walks through the shops every evening comparing the planner's clipboard with what the shops actually have and fixes differences. That is **reconciliation**.
* In a **choreography** version there is no planner: the cake shop phones the castle shop, which phones the invitation printer. Nobody has the whole picture, and it is hard to find out why a party is stuck.

| Analogy | Saga concept |
|---|---|
| Planner | Orchestrator (`OrderSagaOrchestrator`) |
| Clipboard | `saga_instances` and `saga_step_log` tables |
| Cancelling the cake | Compensating action (`ReleaseInventory`) |
| "Party cancelled" note | Semantic compensation, not a rollback |
| Calling again with the same order number | Idempotent command re-sent by `SagaRecoveryJob` |
| Evening walk-through | `ReconciliationService` |
| No planner, shops phone each other | Choreography |
| Party status board for parents | Visible order statuses, `202` plus polling |

## Beginner tier

### Example 1: the steps, the states and the clipboard table

The saga has four forward steps and one compensating step (`SagaStep`): `VALIDATE`, `RESERVE_INVENTORY`, `CONFIRM_ORDER`, `FULFILL`, and `RELEASE_INVENTORY`. The saga itself has a state (`SagaState`):

| Saga state | Meaning |
|---|---|
| `RUNNING` | Forward path in progress |
| `COMPENSATING` | Release of inventory in progress |
| `COMPLETED` | Order fulfilled |
| `ABORTED` | Ended with nothing to compensate (rejected, or cancelled before any reservation) |
| `COMPENSATED` | Compensation finished |
| `FAILED` | Terminal failure |
| `NEEDS_ATTENTION` | Compensation retries exhausted; flagged for an operator |

The clipboard is a table created in `services/order-service/src/main/resources/db/migration/V11__saga_idempotency.sql`:

```sql
CREATE TABLE saga_instances (
    id                    UUID                     NOT NULL PRIMARY KEY,
    order_id              UUID                     NOT NULL,
    state                 VARCHAR(24)              NOT NULL,
    current_step          VARCHAR(32)              NOT NULL,
    attempts              INTEGER                  NOT NULL DEFAULT 0,
    last_error            VARCHAR(1000),
    reason                VARCHAR(32),             -- compensation reason: CANCELLATION | FULFILLMENT_FAILURE | EXPIRED
    reservation_id        UUID,                    -- learned from InventoryReserved (reconciliation input)
    reservation_released  BOOLEAN                  NOT NULL DEFAULT FALSE,
    fulfillment_started   BOOLEAN                  NOT NULL DEFAULT FALSE,
    version               BIGINT                   NOT NULL DEFAULT 0,
    ...
    CONSTRAINT uq_saga_order UNIQUE (order_id),
```

One row per order (`UNIQUE (order_id)`), a `version` column for optimistic locking (`@Version` in `SagaInstance`), and a `saga_step_log` table that records every step with a status of `STARTED`, `SUCCEEDED`, `FAILED`, `RETRIED`, `COMPENSATED` or `SKIPPED`. That one table answers the operator question "where is order X stuck and why" (`GET /api/v1/admin/saga/{orderId}`), which was ADR-0003's first decision driver.

### Example 2: the happy path as commands and events

Orchestration means the orchestrator sends **commands** and participants answer with **events** on their own topics:

| Step | Who does what | Order status after |
|---|---|---|
| accept | `POST /orders` writes order, saga row and idempotency key in one transaction; returns `202` | `CREATED` |
| `VALIDATE` | orchestrator calls inventory (synchronous REST, breaker plus retry) | `VALIDATED` (or `REJECTED`) |
| `RESERVE_INVENTORY` | command `ReserveInventory` to `oms.inventory.commands.v1`; inventory answers `InventoryReserved` or `InventoryReservationFailed` | `INVENTORY_RESERVED` (or `REJECTED`) |
| `CONFIRM_ORDER` | on `InventoryReserved`: local confirm, publish `OrderConfirmed` plus command `StartFulfillment` | `CONFIRMED` |
| `FULFILL` | fulfillment simulates picking and shipping, answers `FulfillmentCompleted` | `FULFILLED` |

The confirm step is a good snippet to read closely (`OrderSagaOrchestrator`):

```java
/** INVENTORY_RESERVED -> CONFIRMED, then publish OrderConfirmed + StartFulfillment (one transaction). */
private void confirmAndStartFulfillment(CustomerOrder order, SagaInstance saga, UUID causation) {
    Decision confirm = transitions.apply(order, OrderTrigger.CONFIRM, null, Actors.SAGA);
    if (!confirm.isTransition()) {
        return;
    }
    logStep(saga, SagaStep.CONFIRM_ORDER, StepStatus.SUCCEEDED, 1, "order confirmed");
    saga.advanceTo(SagaStep.FULFILL, Instant.now());
    logStep(saga, SagaStep.FULFILL, StepStatus.STARTED, 1, "StartFulfillment sent");
    messaging.orderConfirmed(order, causation);
    messaging.startFulfillment(order, causation);
}
```

Two details that interviewers like: the status change, the history row, the step-log rows, the audit row and **both** outbox rows commit or roll back together (one transaction, chapter 05), and messages are only emitted `if (confirm.isTransition())`, so a replay of the same trigger does not create duplicate outbox rows. The ordering of `OrderCreated` is a documented deviation from the original contract wording: because its payload carries the price snapshot, it is published when the order is priced (`CREATED` to `VALIDATED`), in the same outbox batch as `ReserveInventory`; an order rejected at validation emits only `OrderRejected` (contract section 13).

### Example 3: the order state machine as a pure function

The order lifecycle is a table-driven function with no Spring, no I/O and no clock, so every pair is unit-testable (`services/order-service/src/main/java/com/enterprise/oms/order/domain/OrderStateMachine.java`):

```java
on(OrderTrigger.RESERVATION_SUCCEEDED).move(VALIDATED, INVENTORY_RESERVED)
        .alreadyApplied(INVENTORY_RESERVED, CONFIRMED, FULFILLED, COMPENSATION_PENDING);
on(OrderTrigger.RESERVATION_FAILED).move(VALIDATED, REJECTED)
        .alreadyApplied(REJECTED);
on(OrderTrigger.CONFIRM).move(INVENTORY_RESERVED, CONFIRMED)
        .alreadyApplied(CONFIRMED, FULFILLED, COMPENSATION_PENDING);
...
on(OrderTrigger.CANCEL_REQUESTED)
        .move(CREATED, CANCELLED).move(VALIDATED, CANCELLED)
        .move(INVENTORY_RESERVED, COMPENSATION_PENDING).move(CONFIRMED, COMPENSATION_PENDING);
```

`decide(status, trigger)` returns one of three outcomes (`Kind`): `TRANSITION` (valid, with the new status), `NO_OP` (the effect is already in place, so a duplicate or replayed event is harmless) or `INVALID`. For user commands `INVALID` becomes `409 INVALID_STATE_TRANSITION` (`require`); for asynchronous events the orchestrator ignores, counts and audits it. Terminal states are `FULFILLED`, `REJECTED`, `CANCELLED` and `FAILED`. The statuses beyond the happy path are `COMPENSATION_PENDING` (release in flight) and `FAILED`. Why a pure function: exhaustive tests (the order-service README reports 207 state-machine tests covering every status and trigger combination), no hidden I/O, and one place to change a rule. The only code that mutates `CustomerOrder.status` is `OrderTransitions`, which asks the state machine and then writes status, history and audit in the caller's transaction (`Propagation.MANDATORY`).

## Intermediate tier

### Example 1: anatomy of an event handler, and a race that is handled on purpose

Every inbound reply passes through the router (`OrderEventRouting`), which wraps the handler in the `processed_events` transaction (chapter 05), so a handler like `onInventoryReserved` runs exactly as one atomic unit. It also shows the **late reservation after cancel** case, a genuine distributed race:

```java
@Transactional
public void onInventoryReserved(UUID eventId, EventPayloads.InventoryReserved p) {
    Loaded l = load(p.orderId(), "InventoryReserved");
    if (l == null) {
        return;
    }
    CustomerOrder order = l.order();
    SagaInstance saga = l.saga();
    if (saga.getReservationId() == null) {
        saga.setReservationId(p.reservationId());
    }
    if (order.getStatus() == OrderStatus.CANCELLED) {
        lateReservationAfterCancel(order, saga, eventId);
        return;
    }
    Decision d = transitions.apply(order, OrderTrigger.RESERVATION_SUCCEEDED, null, Actors.SAGA);
```

If the customer cancelled while the reservation was in flight, the order is already `CANCELLED` when the reply arrives. The handler does not resurrect the order: it records the reservation id and sends `ReleaseInventory` (reason `CANCELLATION`) so the stock is given back, and the order stays `CANCELLED`. `load` ignores (and counts) events for unknown orders instead of throwing, so a stray event never poisons the consumer. Remember the reservation id: `ReconciliationService` later uses it to verify the release really happened.

### Example 2: compensation is semantic, not a rollback

There are two cancellation shapes (`OrderSagaOrchestrator.cancel`): before any reservation exists the order goes straight to `CANCELLED` (nothing to undo); afterwards it goes to `COMPENSATION_PENDING` and a release command is sent first.

```java
if (d.to() == OrderStatus.CANCELLED) {
    saga.setState(SagaState.ABORTED, Instant.now());
    logStep(saga, saga.getCurrentStep(), StepStatus.SKIPPED, 0, "cancelled before any reservation: " + reason);
    messaging.orderCancelled(order, reason, null);
} else {
    saga.startCompensation(ReleaseReasons.CANCELLATION, Instant.now());
    logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.STARTED, 1, "cancel requested; ReleaseInventory sent");
    messaging.releaseInventory(order, ReleaseReasons.CANCELLATION, null);
    metrics.compensation(ReleaseReasons.CANCELLATION);
}
```

When `InventoryReleased` comes back, `onInventoryReleased` finishes the story: if the saga reason is `FULFILLMENT_FAILURE` the order ends `FAILED`, otherwise `CANCELLED`. The three compensation triggers are customer or operator cancel, fulfillment failure after its own retries (for example the deterministic `FAILTOWN` shipping city), and reservation expiry (TTL 900 s). A **semantic** compensation leaves a trace: the reservation becomes `RELEASED`, a stock movement is recorded, audit rows are written. Not everything is compensable: an email that was sent stays sent, so notification is deliberately **outside** the compensable path and a failed notification never changes order state.

The participant side must also be defensive. Inventory's release logic (`ReservationLifecycleService.release`) writes a **tombstone** when asked to release a reservation that does not exist yet:

```java
if (r == null) {
    UUID tombstoneId = UUID.randomUUID();
    if (!claims.claimReleasedTombstone(tombstoneId, orderId, reason, now)) {
        continue;   // a reservation appeared concurrently: re-read it
    }
    // blocks a later/duplicate ReserveInventory of this (cancelled) order from ever taking stock
```

Because the command and its target can arrive in either order, "release before reserve" is made safe by inserting a `RELEASED` row into the one-slot-per-order table, so the later reserve finds the slot taken and returns the original outcome. This is the standard answer to "what if the compensation overtakes the action".

### Example 3: duplicate-safe transitions (`NO_OP` versus `INVALID`)

`OrderTransitions` is where idempotency of the saga is enforced (`services/order-service/src/main/java/com/enterprise/oms/order/service/OrderTransitions.java`):

```java
Decision d = OrderStateMachine.decide(order.getStatus(), trigger);
switch (d.kind()) {
    case TRANSITION -> record(order, d.to(), trigger, reason, actor);
    case NO_OP -> log.debug("Order {} already past {} (status {}): no-op", order.getId(), trigger,
            order.getStatus());
    case INVALID -> {
        log.info("Order {} ignores {} in status {}", order.getId(), trigger, order.getStatus());
        metrics.ignoredEvent(trigger.name());
        ...
        audit.record(actor, "Order", order.getId(), "TRANSITION_IGNORED", detail);
```

Three duplicate sources are covered: Kafka redelivery (the `processed_events` layer), the recovery job deliberately re-sending a command with a **new** `eventId` (the receiver's unique-reservation rule and the state machine's no-ops), and a user's double click (`applyStrict` throws `409` for an invalid user command, while `Idempotency-Key` protects creation). The three layers are described in ADR-0005.

### Example 4: the user's view of eventual consistency

`POST /orders` returns `202` with status `CREATED`, and the first saga step is started **after commit** so the request thread is free (`SagaTrigger`):

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderAccepted(OrderAccepted event) {
    ...
    executor.execute(() -> {
        try {
            orchestrator.validate(event.orderId());
```

`@TransactionalEventListener(AFTER_COMMIT)` guarantees validation never starts for an order that did not commit. If that hand-off is lost (crash, `TaskRejectedException` from a full executor) nothing is lost: the order stays `CREATED` and the recovery job finds it. The Angular order screen polls with growing delay (`pollUntil` in `frontend/oms-ui/src/app/core/state/polling.ts`, 1500 ms growing to 8000 ms per `saga-and-consistency.md`) until a terminal status, shows the timeline from `GET /orders/{id}/history`, and shows the status reason for `REJECTED` and `FAILED`. Honest UX rules that follow: insufficient stock appears seconds later as `REJECTED` with a reason (not as a synchronous error); cancelling a `CONFIRMED` order passes through `COMPENSATION_PENDING` and the UI shows that intermediate status; early order snapshots can show `totalAmount = 0.00` until validation priced the order.

## Advanced tier

### Example 1: timeouts, retries and the recovery job

Distributed systems cannot rely on a reply arriving, so the saga has a clock. `SagaRecoveryJob` (`services/order-service/src/main/java/com/enterprise/oms/order/saga/SagaRecoveryJob.java`) runs every 10 s (`oms.saga.recovery-interval-ms`), finds sagas in `RUNNING` or `COMPENSATING` with no progress for `oms.saga.stuck-after-seconds` (60, or 30 in the `local` profile) and re-drives them:

```java
Instant cutoff = Instant.now().minus(Duration.ofSeconds(props.getStuckAfterSeconds()));
List<UUID> stuck = tx.execute(s -> sagas.findStalled(List.of(SagaState.RUNNING, SagaState.COMPENSATING), cutoff,
        PageRequest.of(0, props.getRecoveryBatchSize())).stream().map(SagaInstance::getOrderId).toList());
```

`OrderSagaOrchestrator.recover` chooses the action by order status: `CREATED` validates again (after the transaction commits), `VALIDATED` re-sends `ReserveInventory`, `INVENTORY_RESERVED` confirms, `CONFIRMED` re-sends `StartFulfillment`, `COMPENSATION_PENDING` re-sends `ReleaseInventory`. Each re-drive increments the attempt counter, and every command is idempotent at its receiver, so repeating is always safe. After `oms.saga.max-attempts` (default 5) `exhaust` applies the policy: a non-compensable step (`CREATED`, `VALIDATED`, `INVENTORY_RESERVED`) fails the order and **defensively releases** any reservation whose reply may have been lost; a `CONFIRMED` order is treated like a fulfillment failure and compensated; a stuck compensation sets the saga to `NEEDS_ATTENTION`, raises an alert metric and leaves the order in `COMPENSATION_PENDING` for an operator.

```java
case CONFIRMED -> failFulfillment(order, saga, why, null);
case COMPENSATION_PENDING -> {
    saga.setState(SagaState.NEEDS_ATTENTION, Instant.now());
    saga.recordError(why, Instant.now());
    logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.FAILED, saga.getAttempts(), why);
}
```

The manual path (`POST /api/v1/admin/orders/{id}/retry`, `recover(orderId, true)`) resets the attempt counter and re-opens a `NEEDS_ATTENTION` saga; for a terminal order it is `409` ("terminal means terminal"). Other time bounds: reservation TTL (inventory expires a still-`RESERVED` reservation after 900 s, emits `InventoryReleased{reason: EXPIRED}`, and order-service moves an order still in `VALIDATED` or `INVENTORY_RESERVED` to `FAILED`); the Kafka retry budget (about 3.5 s before DLT, chapter 05). Interview point: there are three nested timeouts (HTTP, message retry, saga stuck threshold) and they must be ordered from short to long.

### Example 2: reconciliation as the last safety net

`ReconciliationService` (`services/order-service/src/main/java/com/enterprise/oms/order/saga/ReconciliationService.java`) runs every 5 minutes (`oms.reconciliation.interval-ms`) and on demand (`POST /api/v1/admin/reconciliation/run`, ADMIN only, returning `{examined, repaired, details[]}`). It only touches inconsistencies older than `min-age-seconds` (120) so in-flight work is left alone, and it finds three families from the order service's **own** database:

1. A terminal saga (`ABORTED`, `COMPENSATED`, `FAILED`) that knows a `reservation_id` but never saw `InventoryReleased` (`reservation_released = false`): re-issue `ReleaseInventory`. This covers "order terminal but reservation not released" and "CANCELLED order with reservation `RESERVED`".
2. A `RUNNING` saga at step `FULFILL` where no `FulfillmentStarted` ever came back: re-issue `StartFulfillment`.
3. A saga in `NEEDS_ATTENTION`: re-open it and re-issue the release.

Every repair re-sends an idempotent command, writes a `saga_step_log` row (`RETRIED`) and an `ORDER_RECONCILED` audit event. The Javadoc is candid about the limit: it **cannot see** inventory's or fulfillment's actual data. If the other side is the one that is wrong (for example a reservation whose reply was lost **and** whose command was never processed), the only trigger is the order-side symptom, and a real fix relies on inventory's own expiry job. Contrast recovery and reconciliation: recovery is per-saga, time-triggered and bounded by attempts; reconciliation is a periodic sweep for cross-service invariants that recovery gave up on or never noticed. A saga without a recovery path is a data-corruption machine; this one has three (retry, flag for a human, sweep).

### Example 3: concurrency inside the saga (`@Version` and retrying transactions)

Several actors touch the same order at once: a Kafka consumer thread handling an event, an HTTP thread cancelling, the recovery job, the reconciliation job, possibly on several instances. Optimistic locking arbitrates. `SagaInstance` and `CustomerOrder` both carry `@Version`; whichever transaction commits second gets an `OptimisticLockingFailureException`. `RetryingTransactions` re-runs the whole unit of work from scratch:

```java
public <T> T run(Supplier<T> work) {
    ConcurrencyFailureException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
            return template.execute(status -> work.get());
        } catch (ConcurrencyFailureException e) {
            last = e;
```

The Javadoc explains why re-execution (not blind re-commit) is what makes `@Version` safe: the retry re-reads the winner's state and decides again. Example: a cancel that lost against a saga event now sees the order is already `CONFIRMED` and answers correctly (compensation), instead of overwriting a status it never saw. In the Kafka path the failed handler throws, the container retries the record, and the `processed_events` row rolled back with it, so the second attempt is a clean first attempt. Cancel itself (`OrderCommandService.cancel`) runs inside `retryTx.run`, then calls `orders.flush()` to surface a version conflict immediately.

### Example 4: orchestration, choreography, workflow engines and saga isolation

Be ready to argue the choice (ADR-0003):

| Style | Strength | Weakness | Here |
|---|---|---|---|
| Choreography | No central owner, loose coupling | Implicit flow, cyclic dependencies, hard to answer "where is it stuck" | Used only for notification and inventory's `OrderConfirmed`/`OrderCancelled` safety net |
| Orchestration | One table shows the flow, explicit compensation, single owner of recovery | Orchestrator is a coordination hot spot and must be crash-safe and highly available | Primary pattern |
| Workflow engine (Temporal, Camunda, Step Functions) | Durable timers, retries, visibility out of the box | Heavy runtime; cannot run in the Docker-less local stack | Rejected for this demo; right answer for long, complex production flows |

A saga gives atomicity, consistency and durability but **not isolation**: other transactions can see intermediate states. Known countermeasures and where they appear here: a **semantic lock** (the reservation in status `RESERVED` marks stock as promised, and the order status shows `VALIDATED` or `INVENTORY_RESERVED`); **commutative updates** (`reserved = reserved + :q`, `reserved = reserved - :q` give the same total in any order); a **pessimistic view** (reorder so the riskiest, least reversible step comes late; notification last, outside the saga); **reread value** (the `@Version` check plus retry); and **bounded lifetimes** (TTL on reservations). The list of anomalies to name: lost updates, dirty reads of intermediate states, and fuzzy reads.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Orchestrator, steps, compensation | `services/order-service/src/main/java/com/enterprise/oms/order/saga/OrderSagaOrchestrator.java` | Every handler is one transaction |
| Saga state and log | `services/order-service/src/main/java/com/enterprise/oms/order/entity/SagaInstance.java`, `SagaStepLog.java`, `services/order-service/src/main/resources/db/migration/V11__saga_idempotency.sql` | `@Version`, unique `order_id` |
| Order state machine | `services/order-service/src/main/java/com/enterprise/oms/order/domain/OrderStateMachine.java`, `OrderStatus.java`, `OrderTrigger.java`, `services/order-service/src/main/java/com/enterprise/oms/order/service/OrderTransitions.java` | Pure, table driven |
| Event routing | `services/order-service/src/main/java/com/enterprise/oms/order/saga/OrderEventRouting.java`, `OrderEventsListener.java` | Idempotent via `processed_events` |
| Messages via outbox | `services/order-service/src/main/java/com/enterprise/oms/order/saga/SagaMessaging.java`, `services/platform-common/src/main/java/com/enterprise/oms/common/outbox/*` | Same transaction as state change |
| Recovery | `services/order-service/src/main/java/com/enterprise/oms/order/saga/SagaRecoveryJob.java`, `OrderSagaOrchestrator.recover/exhaust` | 60 s stuck, 5 attempts |
| Reconciliation | `services/order-service/src/main/java/com/enterprise/oms/order/saga/ReconciliationService.java`, `AdminController` | Scheduled and admin endpoint |
| Concurrency | `services/order-service/src/main/java/com/enterprise/oms/order/service/RetryingTransactions.java`, `OrderCommandService.java` | Optimistic locking plus retry |
| After-commit trigger | `services/order-service/src/main/java/com/enterprise/oms/order/saga/SagaTrigger.java`, `services/order-service/src/main/java/com/enterprise/oms/order/config/OrderServiceConfig.java` | Executor off the request thread |
| Participant idempotency | `services/inventory-service/src/main/java/com/enterprise/oms/inventory/service/ReservationService.java`, `ReservationLifecycleService.java`, `services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/ReservationClaims.java` | One slot per order, tombstones |
| Operator tools | `GET /api/v1/admin/saga/{orderId}`, `POST /api/v1/admin/orders/{id}/retry`, `POST /api/v1/admin/reconciliation/run`, `docs/runbooks/01-orders-stuck-processing.md` | Audited |
| Tests | `OrderStateMachineTest`, `OrderSagaOrchestratorTest`, `SagaRecoveryTest`, `ReconciliationTest`, `OrderSagaKafkaIT` | H2 and embedded KRaft |

### Demonstration-only vs production-grade

* **Production-grade ideas**: visible, persisted saga state; pure and exhaustively tested state machine; idempotent commands everywhere; compensation paths for cancel, fulfillment failure and expiry; recovery plus reconciliation; audit trail for every transition and repair; a defensive release when a reply may have been lost; operator endpoints that are idempotent and audited.
* **Demonstration-only / limits**: the simulated fulfillment and notification services (the `FAILTOWN` city and `fail.example.test` domain are deterministic failure hooks); the orchestrator is a single logical component with no standby leadership design beyond optimistic locking; the recovery and reconciliation jobs run on every replica (safe because of `@Version` and idempotent commands, but wasteful; the README recommends a scheduler lock before running many replicas); reconciliation cannot read the other services' data; there are no business-level compensation for money movement because there is no payment step; no timer wheel or saga-level deadline beyond `stuck-after` and `max-attempts`.
* **Verification status**: the flow is covered by unit and H2 tests (order-service README: 433 unit and H2 tests, 20 embedded-Kafka tests covering happy path, insufficient stock, cancellation with compensation, fulfillment failure, duplicates, out-of-order events, poison to DLT, recovery and reconciliation). Multi-instance behaviour, real PostgreSQL lock behaviour and broker-failure injection have not been run on the dev machine because Docker is not installed, and Testcontainers tests are skipped there.

## Common mistakes and gotchas

* Treating a saga as a distributed transaction and expecting isolation or instant rollback.
* Compensations that are not idempotent, or that fail when the original action never happened (release before reserve). Use tombstones or "no-op if absent".
* Retrying a step without idempotent receivers, producing duplicate side effects.
* Publishing events outside the state-change transaction (use the outbox) or from inside a handler before commit.
* Forgetting the late-reply cases: a reservation arriving after cancel, an event for an order that is already terminal, a replayed event after the order moved on. Encode them as state machine no-ops.
* An orchestrator that holds a DB transaction open across a remote call. Here `validate` reads, calls, then writes in separate transactions.
* No clock: sagas without timeouts, stuck-detection or a retry cap leave orders hanging forever. Equally, retry without a cap hammers a dead dependency.
* Compensating something that cannot be undone (an email). Order the saga so irreversible steps come last, or accept the semantic note.
* Using wall-clock deadlines that differ between services without a TTL on the reserved resource.
* Hiding intermediate states from users: `COMPENSATION_PENDING` must be shown honestly, with polling and a reason.
* Updating `orders.status` with SQL: it bypasses history, audit and outbox rows (runbook 01).
* Resetting consumer offsets to "fix" a saga: it skips or repeats steps.
* Assuming reconciliation can fix anything: it can only re-issue idempotent commands for what the order side can see.

## Quick-recall cheat sheet

* Saga = local transactions plus compensations; no global rollback, no isolation; intermediate states are visible.
* Orchestration: one owner (`OrderSagaOrchestrator`) sends commands, listens to events, persists state (`saga_instances`, `saga_step_log`). Choreography: no owner, implicit flow. Workflow engine: durable orchestration as a product.
* Steps: `VALIDATE`, `RESERVE_INVENTORY`, `CONFIRM_ORDER`, `FULFILL`; compensation `RELEASE_INVENTORY`.
* Order statuses: `CREATED`, `VALIDATED`, `INVENTORY_RESERVED`, `CONFIRMED`, `FULFILLED`, `REJECTED`, `CANCELLED`, `COMPENSATION_PENDING`, `FAILED` (terminal: `FULFILLED`, `REJECTED`, `CANCELLED`, `FAILED`).
* Saga states: `RUNNING`, `COMPENSATING`, `COMPLETED`, `ABORTED`, `COMPENSATED`, `FAILED`, `NEEDS_ATTENTION`.
* State machine decisions: `TRANSITION`, `NO_OP` (duplicate), `INVALID` (409 for commands, ignored and audited for events).
* Handlers run in the `processed_events` transaction; state, history, saga log, audit and outbox rows commit together.
* Compensation is semantic: release leaves `RELEASED` rows, movements and audit; irreversible effects (email) stay outside.
* Release-before-reserve is safe via a `RELEASED` tombstone; late reservation after cancel triggers a release.
* Recovery: stuck after 60 s, retry up to 5 times by re-sending idempotent commands, then fail (non-compensable) or flag `NEEDS_ATTENTION` (compensation).
* Reconciliation: every 5 min or `POST /admin/reconciliation/run`; repairs unreleased reservations, no fulfillment progress, stuck compensations; cannot see other services' data.
* Concurrency: `@Version` plus `RetryingTransactions` re-executes from scratch; jobs on every replica are safe but redundant.
* UX: `202` plus polling with backoff; show `COMPENSATION_PENDING`; operators use saga view, retry and reconciliation.
* Isolation countermeasures to name: semantic lock, commutative updates, pessimistic ordering, reread value, TTL.
