# Mentoring: SOLID in the OMS

Snippets marked `sketch` follow the contract but are not copies of committed code (order-service and inventory-service exist on disk now; the real classes may differ in detail). Snippets marked `verified` reference classes that exist under `services/platform-common`.

---

## 1. Single Responsibility: the 600-line `OrderService`

**PR:** "Add order creation and saga handling" - one `OrderService` class.

```java
// sketch - BEFORE
@Service
public class OrderService {
    public Order create(CreateOrderRequest req, String idemKey) {
        // 1. idempotency lookup + hash compare
        // 2. validate products via REST to inventory
        // 3. compute totals
        // 4. persist order + items
        // 5. write status history + audit
        // 6. publish OrderCreated to Kafka
        // 7. send "order received" email text
        // 8. on InventoryReserved event: transition + start fulfillment
    }
}
```

**Reviewer:** Count the reasons this class would change. Idempotency policy, pricing, inventory client contract, status history format, event schema, notification wording, saga rules. That is seven.

**Junior:** It is all "order stuff", and one `@Transactional` keeps it consistent.

**Reviewer:** Consistency comes from the transaction boundary, not from the class boundary. We want one transaction that spans: state change, history row, audit row, outbox row. That is a *use case* (`OrderCommandService.create`), and it can call collaborators that each own one concern. Also, point 7 does not belong in order-service at all: the contract says notification-service consumes `OrderCreated` and owns notification text.

**Better version (sketch):**

```java
@Service
class CreateOrderUseCase {
    private final IdempotencyGate idempotency;     // key + request hash policy
    private final ProductCatalogClient catalog;    // adapter to inventory REST
    private final OrderPricing pricing;            // pure, unit-testable
    private final OrderRepository orders;
    private final OrderStateMachine machine;       // pure domain (OrderStateMachine, exists in order/domain)
    private final OutboxService outbox;            // verified: platform-common

    @Transactional
    Order execute(CreateOrderCommand cmd) {
        return idempotency.replayOr(cmd, () -> {
            var lines = catalog.validate(cmd.items());     // outside the DB tx? see below
            var order = Order.create(cmd, pricing.price(lines));
            orders.save(order);
            outbox.append(EventFactory.orderCreated(order));
            return order;
        });
    }
}
```

**Junior:** You called the REST client inside `@Transactional`. Is that okay?

**Reviewer:** Good catch, and no: a 3 s read timeout (contract section 5) would hold a DB connection for 3 s per request. Validate first, then open the transaction. Mentoring item for `performance.md`. SRP made that visible because the call is now a named collaborator you can move.

**Takeaway**
- [ ] Can you state the class's purpose without "and"?
- [ ] Pure rules (pricing, state transitions) are in classes with no Spring or JPA imports.
- [ ] A class that owns the transaction does not also own policy.

---

## 2. Open/Closed: adding an event type without editing a switch

**PR:** new `OrderFailed` handling in the notification consumer.

```java
// sketch - BEFORE
switch (envelope.eventType()) {
    case "OrderCreated"   -> handleCreated(envelope);
    case "OrderConfirmed" -> handleConfirmed(envelope);
    // ...every new event edits this method in 4 services
    default -> log.warn("unknown {}", envelope.eventType());
}
```

**Reviewer:** The platform already gives you the open/closed shape. `EventRouter` / `EventRouterFactory` in `services/platform-common/.../messaging/` register handlers per event type, count unknown types as `oms.events.ignored`, and the contract demands unknown types be ignored, not thrown. Register a handler; do not reopen the switch.

**Junior:** But a map of lambdas is just a switch in disguise.

**Reviewer:** The difference is where change lands. A new event = a new handler class + one registration line + its own test. Existing handlers are not recompiled in a reviewer's head. Also the "default" branch behaviour (ignore + count) lives in one place, so no service can accidentally throw and DLT a forward-compatible event.

**Takeaway**
- [ ] New behaviour = new class, not a new `case`.
- [ ] Unknown inputs have one shared policy (ignore + metric).

---

## 3. Liskov: a `StockPolicy` that throws on `release`

**PR:** adds `ReadOnlyStockView implements StockOperations` for the customer product API.

```java
// sketch - BEFORE
interface StockOperations {
    int available(UUID productId);
    void reserve(UUID productId, int qty);
    void release(UUID productId, int qty);
}
class ReadOnlyStockView implements StockOperations {
    public void reserve(UUID p, int q) { throw new UnsupportedOperationException(); }
    public void release(UUID p, int q) { throw new UnsupportedOperationException(); }
}
```

**Reviewer:** Any caller holding a `StockOperations` now has a landmine. If the reservation service ever receives this subtype by mistake (a DI qualifier typo), the failure happens at runtime, mid-transaction, after the outbox row may already be written.

**Better version (also ISP):**

```java
interface StockQuery { int available(UUID productId); }
interface StockReservation { ReservationResult reserve(UUID orderId, List<Line> lines); void release(UUID orderId); }
class JdbcStockRepository implements StockQuery, StockReservation { ... }
class ReadOnlyStockView implements StockQuery { ... }
```

**Takeaway**
- [ ] A subtype never strengthens preconditions or throws `UnsupportedOperationException` for an inherited method.
- [ ] If you are tempted to, split the interface.

---

## 4. Interface Segregation: the fat repository

**Reviewer:** The reservation consumer needs two operations: "try to reserve atomically" and "load reservation by orderId". It does not need `findAll(Pageable)`, `save`, `delete`. Inject a narrow port:

```java
// sketch
interface ReservationPort {
    Optional<Reservation> findByOrderId(UUID orderId);
    boolean tryReserve(UUID productId, int qty);   // affected rows == 1
}
```

Spring Data repository implements it behind an adapter. Benefit: the Mockito test for the consumer stubs two methods, and nobody can call `deleteAll()` from a Kafka listener.

**Takeaway**
- [ ] Constructor parameters are the smallest interface the class actually uses.
- [ ] Listeners/controllers never receive a full CRUD repository when two methods are enough.

---

## 5. Dependency Inversion: `IdempotentEventProcessor` takes a `Runnable`

**Verified:** `services/platform-common/src/main/java/com/enterprise/oms/common/idempotency/IdempotentEventProcessor.java` exposes `boolean processOnce(String consumer, EventEnvelope<?> envelope, Runnable effect)`.

**Junior:** Why pass a `Runnable` instead of an interface `EventHandler`?

**Reviewer:** The processor's job is "insert into `processed_events(consumer, event_id)` and run the effect in the same transaction; if the unique constraint says duplicate, skip and bump `oms.events.duplicates`". It must not know about orders or reservations, so the business effect is injected. The high-level policy (exactly-once effect) depends on an abstraction (`Runnable`), the low-level details (reservation SQL) depend on the same abstraction. Test it with a lambda that increments a counter, which is exactly what `IdempotentProcessorAndAuditTest` can do.

```java
// sketch - usage in inventory consumer
boolean applied = processor.processOnce("inventory-service", envelope,
        () -> reservations.reserve(payload.orderId(), payload.items()));
```

**Junior:** What if the effect throws?

**Reviewer:** The transaction rolls back, including the processed_events insert, so redelivery retries the effect. That is why the insert and the effect must be one transaction. If you called `processOnce` outside a transaction you would silently lose that guarantee: look at where `@Transactional` sits (and see the self-invocation scenario in `../code-review/03-transactional-self-invocation.md`).

**Takeaway**
- [ ] Cross-cutting guarantees (idempotency, outbox, audit) are injected as collaborators; business code is passed in.
- [ ] High-level modules do not `new` their dependencies.

---

## Summary checklist for PR reviews

- [ ] One reason to change per class.
- [ ] New event/state = new handler/transition entry, not a bigger switch.
- [ ] Interfaces are narrow; no `UnsupportedOperationException` in implementations.
- [ ] Transactions wrap only DB work; remote calls happen outside.
- [ ] Domain rules are framework-free and unit tested.
