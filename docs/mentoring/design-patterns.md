# Mentoring: Design Patterns in the OMS

Each section: where the pattern appears in this platform, a review dialogue, the better version, and a checklist. `sketch` = teaching reconstruction that follows the contract (the real class now exists on disk and may differ in detail). `verified` = exists in `services/platform-common` or a service module.

---

## 1. Strategy: inventory reservation concurrency

Contract section 7 names three ways to reserve stock: (a) atomic conditional UPDATE (default), (b) `@Version` optimistic locking (admin stock edits), (c) pessimistic `SELECT ... FOR UPDATE` (documented/benchmarked variant).

**Verified on disk (checked when written):** `services/inventory-service/src/main/java/com/enterprise/oms/inventory/reservation/` contains `ReservationStrategy` (`String name()`, `Result tryReserve(List<QuantityItem> sortedLines)`), `AtomicUpdateReservationStrategy`, `PessimisticLockReservationStrategy`, `OptimisticVersionReservationStrategy`, `ReservationStrategies`, plus `service/ReservationService`. The interface contract requires: runs in the caller's transaction, lines merged per product and sorted by productId ascending, all-or-nothing, reports every short line. The sketches below illustrate the review conversation; the real signatures are the ones just listed.

**PR (sketch, BEFORE):**

```java
if ("atomic".equals(mode)) { ... }
else if ("optimistic".equals(mode)) { ... }
else { ... }   // 90 lines of nested branches, mode read from @Value
```

**Reviewer:** Three algorithms with the same inputs and outputs, selected by configuration. That is the Strategy pattern, so make the seam explicit.

```java
// sketch
public interface ReservationStrategy {
    ReservationResult reserve(UUID orderId, List<Line> sortedLines);
}

@Component @ConditionalOnProperty(name="oms.inventory.reservation-strategy", havingValue="atomic", matchIfMissing=true)
class AtomicUpdateStrategy implements ReservationStrategy {
    // UPDATE stock SET reserved = reserved + :q, version = version + 1
    //   WHERE product_id = :id AND on_hand - reserved >= :q    -> rows == 0 => insufficient
}

@Component @ConditionalOnProperty(name="oms.inventory.reservation-strategy", havingValue="pessimistic")
class ForUpdateStrategy implements ReservationStrategy { /* SELECT ... FOR UPDATE */ }
```

**Junior:** Why `matchIfMissing=true` on atomic only?

**Reviewer:** So a missing property yields the safe default, and exactly one bean exists. Two beans without a qualifier give a startup failure, which is also fine, but silent "none selected" is not.

**Junior:** Do the strategies need the same contract tests?

**Reviewer:** Yes: one abstract test class (`ReservationStrategyContract`) with `100 threads x qty 1 vs stock 50 => exactly 50 successes` that every strategy subclasses. That is how you prove they are substitutable (see `testing.md`).

**Also note:** the `sortedLines` parameter. All strategies must lock/update in sorted productId order to avoid deadlocks; encode that as part of the contract (the caller sorts, or the interface type is `SortedLines`).

**Checklist**
- [ ] Algorithms vary behind an interface, chosen once at wiring time.
- [ ] One contract test, run against every implementation.
- [ ] Default is the safe option.

---

## 2. State machine: `OrderStateMachine`

Contract section 6 defines nine states and a transition table; transition logic is a pure domain class with a unit test per valid and invalid pair. Verified on disk: `services/order-service/src/main/java/com/enterprise/oms/order/domain/OrderStateMachine.java` (static `decide(from, trigger)` returning `Decision{kind: TRANSITION|NO_OP|INVALID, to}`, `require`, `isAllowed`, `allowedTargets`) with `OrderStatus`, `OrderTrigger`, and `OrderStateMachineTest`. The snippet below is a simplified teaching sketch of the same idea, not that file.

**PR (BEFORE):**

```java
// sketch
public void cancel(Order o) {
    if (o.getStatus() == FULFILLED) throw new IllegalStateException("cannot cancel");
    o.setStatus(CANCELLED);          // what about COMPENSATION_PENDING? REJECTED?
}
public void confirm(Order o) { o.setStatus(CONFIRMED); }   // no check at all
```

**Reviewer:** Each method hand-checks one forbidden state. Contract says cancel is allowed only from CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED; and from the last two it must go to COMPENSATION_PENDING, not CANCELLED. Also `IllegalStateException` becomes 500; the contract wants 409 `INVALID_STATE_TRANSITION` (see `InvalidStateTransitionException` in `platform-common/.../error/`, verified).

**Better version:**

```java
// sketch
public final class OrderStateMachine {
    private static final Map<Key, OrderStatus> TABLE = Map.of(
        key(CREATED, VALIDATION_OK),            VALIDATED,
        key(CREATED, VALIDATION_FAILED),        REJECTED,
        key(VALIDATED, INVENTORY_RESERVED_EVT), INVENTORY_RESERVED,
        key(INVENTORY_RESERVED, CANCEL),        COMPENSATION_PENDING
        /* ... every row of contract section 6 ... */);

    public Transition apply(OrderStatus from, OrderEvent event) {
        OrderStatus to = TABLE.get(key(from, event));
        if (to == null) {
            if (alreadyApplied(from, event)) return Transition.noop(from);   // idempotent redelivery
            throw new InvalidStateTransitionException(from.name(), event.name());
        }
        return Transition.of(from, to);
    }
}
```

**Junior:** What is `alreadyApplied`?

**Reviewer:** Kafka is at-least-once. If `InventoryReserved` arrives twice and the order is already INVENTORY_RESERVED, the second must be a no-op, not a 409 that sends the record to the DLT. Contract: "applying an already-applied event again is a no-op".

**Junior:** And persisting?

**Reviewer:** The machine only decides. The caller, in one DB transaction, writes `order_status_history`, `saga_step_log`, `audit_events` and the outbox row. Keep decision and persistence apart so the exhaustive 9x(events) test needs no database.

**Checklist**
- [ ] Transitions are data (a table), not scattered `if`.
- [ ] Invalid transition maps to 409, redelivery maps to no-op.
- [ ] Exhaustive unit test: every (state, event) pair asserted either valid or invalid.

---

## 3. Transactional outbox

**Verified:** `OutboxService.append(topic, key, envelope)`, `OutboxRelay`, `OutboxEvent`, `OutboxProperties` under `services/platform-common/.../outbox/`.

**PR (BEFORE):**

```java
@Transactional
public void confirm(UUID orderId) {
    Order o = orders.findById(orderId).orElseThrow();
    o.setStatus(CONFIRMED);
    kafka.send("oms.order.events.v1", orderId.toString(), json(orderConfirmed(o)));  // dual write
}
```

**Reviewer:** Two failure modes. If `send` succeeds and the commit then fails, consumers see `OrderConfirmed` for an order that is not confirmed. If commit succeeds and the broker is down, the event is lost and the customer never gets an email. Different systems, no shared transaction.

**Better version:**

```java
@Transactional
public void confirm(UUID orderId) {
    Order o = orders.findById(orderId).orElseThrow();
    machine.apply(o, OrderEvent.CONFIRM);
    outbox.append("oms.order.events.v1", orderId.toString(), EventFactory.orderConfirmed(o));
}   // relay publishes later: acks=all, idempotent producer, marks published_at
```

**Junior:** So now the relay can publish twice?

**Reviewer:** Yes (relay crash between send and marking). That is why every consumer is idempotent via `processed_events`. Outbox plus idempotent consumer is the pair; one without the other is a bug. Say "effectively-once business effect", never "exactly-once delivery".

**Junior:** What do I monitor?

**Reviewer:** `oms.outbox.pending`. A growing value means the relay or the broker is stuck (runbook `../runbooks/02-kafka-consumer-lag.md`).

**Checklist**
- [ ] No `KafkaTemplate.send` inside business transactions.
- [ ] Consumers insert into `processed_events` in the same tx as the effect.
- [ ] `oms.outbox.pending` has an alert.

---

## 4. Saga (orchestration)

Steps: `VALIDATE -> RESERVE_INVENTORY -> CONFIRM_ORDER -> FULFILL`; compensation `RELEASE_INVENTORY`. Orchestrator: `OrderSagaOrchestrator` (exists, `order/saga/`; tests `OrderSagaOrchestratorTest`, `SagaOrchestrationTest`, `OrderSagaKafkaIT`). Recovery: `SagaRecoveryJob` (exists; `SagaRecoveryTest`). Repair: `ReconciliationService` (exists; `ReconciliationTest`).

**PR (BEFORE):** the inventory consumer calls `orderService.confirm(...)` directly over REST on success, and nothing on failure.

**Reviewer:** There is no owner of the process. If fulfillment fails after stock was reserved, who releases it? In a saga the orchestrator owns three things: the current step, the retry counter, and the compensation. Table `saga_instances(order_id unique, state, current_step, attempts, last_error, reason, version)` and `saga_step_log` hold that state, which is also what `GET /api/v1/admin/saga/{orderId}` returns for operators.

```java
// sketch
@Transactional
void on(InventoryReservationFailed e) {
    saga.step(e.orderId(), RESERVE_INVENTORY, FAILED, e.reason());
    machine.apply(order, REJECT);                 // VALIDATED -> REJECTED, no compensation needed
    outbox.append(ORDER_EVENTS, id, orderRejected(order));
}

@Transactional
void on(FulfillmentFailed e) {
    saga.reason(e.orderId(), FULFILLMENT_FAILURE);
    machine.apply(order, FULFILLMENT_FAILED);     // CONFIRMED -> COMPENSATION_PENDING
    outbox.append(INVENTORY_COMMANDS, id, releaseInventory(id, "FULFILLMENT_FAILURE"));
    metrics.compensations.increment();            // oms.saga.compensations
}
```

**Junior:** Isn't this just a distributed transaction?

**Reviewer:** No, and the docs must not pretend so. States are eventually consistent and compensations are semantic: "release the reservation", not "roll back". There is a window where the order is CONFIRMED and fulfillment has failed. `ReconciliationService` (pending) exists for the cases where compensation itself fails.

**Junior:** What if the compensation command is lost?

**Reviewer:** The `SagaRecoveryJob` finds sagas stuck longer than `oms.saga.stuck-after-seconds`, re-sends the idempotent command (inventory ignores a duplicate `ReleaseInventory` for the same orderId), and after `oms.saga.max-attempts` flags COMPENSATION_PENDING with an alert. Practice that in `../runbooks/01-orders-stuck-processing.md`.

**Checklist**
- [ ] Every forward step has a compensation or is explicitly non-compensable.
- [ ] All commands are idempotent so retry is safe.
- [ ] Stuck-saga detection and an alerting metric exist.

---

## 5. Factory: event envelopes and routers

**Verified:** `EventEnvelope<T>` and `EventJson` in `platform-common/.../events/`; `EventRouterFactory` in `.../messaging/`.

**PR (BEFORE):**

```java
var env = new EventEnvelope<>(UUID.randomUUID().toString(), "OrderConfirmed", 1,
        Instant.now(), corr, null, "Order", order.getId().toString(), "order-service", payload);
```

repeated in 14 places with different mistakes (wrong `aggregateType`, missing `causationId`, producer typo).

**Reviewer:** The envelope has 10 fields, 6 of which are determined by the event type and the service. Put them in a factory so call sites express intent only:

```java
// sketch
final class OrderEvents {
    static EventEnvelope<OrderConfirmed> orderConfirmed(Order o, EventContext ctx) {
        return ctx.envelope("OrderConfirmed", "Order", o.getId(),
                new OrderConfirmed(o.getId(), o.getCustomerId(), o.getCustomerEmail(), o.getTotalAmount()));
    }
}
```

`EventContext` supplies `correlationId`, `causationId` (id of the event being handled), `producer`, clock. Tests inject a fixed clock and UUID supplier, so JSON snapshot assertions are deterministic.

**Checklist**
- [ ] Construction rules live in one factory; call sites cannot misspell `eventType` (use `EventTypes` constants, verified in `platform-common`).
- [ ] Time and ids are injectable.

---

## 6. Adapter: inventory REST client behind a port

**Verified:** `OmsRestClientFactory` and `HttpClientProperties` (`platform-common/.../http/`), `ServiceTokenProvider` (`.../security/`).

**PR (BEFORE):** order code builds `RestClient` calls to `/api/v1/internal/products/validate` inline and handles `HttpClientErrorException` in the use case.

**Reviewer:** The use case should talk in domain language: "validate these lines, get priced lines or reasons". Hide HTTP behind an adapter implementing a port:

```java
// sketch
public interface ProductCatalogClient {
    ValidationResult validate(List<OrderLine> lines);
}

@Component
class InventoryRestCatalogClient implements ProductCatalogClient {
    // RestClient from OmsRestClientFactory (connect 2s / read 3s per contract),
    // SERVICE JWT from ServiceTokenProvider, X-Correlation-Id header,
    // @CircuitBreaker + @Retry (Resilience4j 2.2.0), maps 5xx/timeout -> UpstreamUnavailableException
}
```

**Junior:** Why map to `UpstreamUnavailableException`?

**Reviewer:** The saga needs to distinguish "inventory says invalid" (order REJECTED) from "inventory unreachable" (retry; after max attempts, FAILED). If both surface as a generic exception, the orchestrator cannot choose. The adapter is also the only place that knows the wire DTO; if inventory adds a field (additive-only within v1) nothing else changes.

**Checklist**
- [ ] Remote systems are behind a port named in domain terms.
- [ ] The adapter translates errors into domain-meaningful exceptions.
- [ ] Timeouts and breaker config are set at the adapter, nowhere else.

---

## Pattern selection cheat sheet

| Problem | Pattern | Do not confuse with |
|---|---|---|
| Interchangeable algorithms (reservation) | Strategy | State (state changes *which* behaviour, over time) |
| Legal lifecycle moves | State machine | Strategy |
| DB write + message must both happen | Outbox | Two-phase commit (not used here) |
| Multi-service business process with undo | Saga | Distributed ACID transaction |
| Complex object creation rules | Factory | `new` scattered |
| Foreign API shape differs from domain | Adapter | Facade (simplifies, does not translate) |
