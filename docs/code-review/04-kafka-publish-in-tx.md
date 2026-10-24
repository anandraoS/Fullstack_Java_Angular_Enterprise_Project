# 04 - Kafka publish inside the DB transaction

Severity: **High** (phantom events or lost events; customer-visible inconsistency)

## PR title: "Publish OrderConfirmed when saga confirms the order"

```diff
 @Transactional
 public void confirm(UUID orderId) {
     Order o = orderRepository.findById(orderId).orElseThrow();
     stateMachine.apply(o, OrderEvent.CONFIRM);
     historyRepository.save(OrderStatusHistory.of(o, "saga"));
+    kafkaTemplate.send("oms.order.events.v1", orderId.toString(),
+                       objectMapper.writeValueAsString(OrderEvents.orderConfirmed(o)));
 }
```

## Reasoning

A database and a broker cannot be committed atomically here (no XA/2PC by design). Sending inside the method gives a dual write with two failure windows:

| Failure | Result |
|---|---|
| `send` succeeds (async), then the transaction rolls back (constraint violation, deadlock victim, timeout) | `OrderConfirmed` is on the topic, notification emails the customer "confirmed", inventory commits the reservation, but the order is still INVENTORY_RESERVED. Phantom event. |
| Commit succeeds, `send` fails or process dies before the producer flushes | Order is CONFIRMED, no event. Nobody ships it, nobody is told. Lost event. |
| `send` blocks on broker metadata for `max.block.ms` | DB connection held while waiting (see mentoring/performance.md) |

Also `send` returns a future that is ignored; failures are never observed. And `writeValueAsString` throws a checked exception which the PR wraps in nothing, so a serialisation bug rolls back a legitimate state change.

Contract section 7 mandates the transactional outbox: the event row is inserted in the same DB transaction as the state change; `OutboxRelay` publishes afterwards with `acks=all` and idempotent producer, so delivery is at-least-once, and consumers dedupe via `processed_events`.

## Fix

```java
@Transactional
public void confirm(UUID orderId) {
    Order o = orderRepository.findById(orderId).orElseThrow();
    stateMachine.apply(o, OrderEvent.CONFIRM);
    historyRepository.save(OrderStatusHistory.of(o, "saga"));
    outboxService.append("oms.order.events.v1", orderId.toString(),     // OutboxService: platform-common (verified)
                         OrderEvents.orderConfirmed(o));
    outboxService.append("oms.fulfillment.commands.v1", orderId.toString(),
                         OrderEvents.startFulfillment(o));              // command in the same unit of work
}
```

Review for the outbox rows: topic constant from `Topics` (verified), key = orderId, envelope built by the factory with `correlationId` and `causationId` set.

## Regression tests

1. Unit/integration: the outbox row exists iff the state change committed.

```java
// sketch (H2)
@Test
void confirm_writesStateAndOutboxAtomically() {
    UUID id = persistOrder(INVENTORY_RESERVED);
    service.confirm(id);
    assertThat(orderRepository.findById(id).get().getStatus()).isEqualTo(CONFIRMED);
    assertThat(outboxEventRepository.findByAggregateId(id.toString()))
        .extracting(OutboxEvent::getEventType).contains("OrderConfirmed", "StartFulfillment");
}

@Test
void confirm_rollsBackOutboxWhenHistoryInsertFails() {
    UUID id = persistOrder(INVENTORY_RESERVED);
    doThrow(new DataIntegrityViolationException("x")).when(historyRepository).save(any());
    assertThatThrownBy(() -> service.confirm(id)).isInstanceOf(DataAccessException.class);
    assertThat(outboxEventRepository.findByAggregateId(id.toString())).isEmpty();
}
```

2. Architecture guard: forbid `KafkaTemplate` in business packages.

```java
// sketch (ArchUnit, optional dependency)
@ArchTest
static final ArchRule noDirectKafkaSendInServices = noClasses()
    .that().resideInAPackage("..oms.order..service..")
    .should().dependOnClassesThat().areAssignableTo(org.springframework.kafka.core.KafkaTemplate.class);
```

3. The relay itself is covered by `OutboxRelayKafkaTest` (verified on disk, EmbeddedKafka, no Docker).

## Reviewer checklist from this scenario

- [ ] `grep -rn KafkaTemplate` in business code returns nothing.
- [ ] Outbox append is in the same transaction as the state change.
- [ ] Consumers of this event are idempotent.
- [ ] `oms.outbox.pending` has an alert.
