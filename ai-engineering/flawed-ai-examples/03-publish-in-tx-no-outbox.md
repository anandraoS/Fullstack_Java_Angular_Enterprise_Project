# 03 Publishing to Kafka inside the transaction (no outbox)

Code: `java/.../Ex03DualWrite.java`
Test: `Ex03DualWriteTest` (5 tests)

## Prompt that produces it
"After saving the order, publish an OrderCreated event to Kafka."

## Flawed code (two common shapes)
```java
// Shape A: publish inside the transaction
@Transactional
public void createOrder(CreateOrder cmd) {
    orders.save(order);
    kafka.send("oms.order.events.v1", orderId, json);   // cannot be rolled back
}   // commit may still fail (constraint, connection loss) -> phantom event

// Shape B: publish after commit
orders.save(order);            // committed
kafka.send(...);               // broker down / process dies -> event lost forever
```

## Why it is risky here
The database and Kafka cannot share one atomic commit (no distributed ACID, see
`docs/architecture/delivery-semantics.md`). Shape A tells inventory and notification about an order that does not
exist. Shape B leaves a CREATED order that no one ever reserves stock for; the customer sees an order stuck in
`CREATED` and only the recovery job notices. Both are silent in testing because the broker is always up.

## Corrected code: transactional outbox
```java
@Transactional
public void createOrder(CreateOrder cmd) {
    orders.save(order);
    outbox.append(Topics.ORDER_EVENTS, orderId.toString(), envelope);   // same DB transaction
}
// OutboxRelay (scheduled) publishes pending rows with acks=all + idempotent producer, then marks published_at.
```
Real classes: `com.enterprise.oms.common.outbox.OutboxService.append(...)` and `OutboxRelay` in
`services/platform-common`. The outbox is at-least-once: a crash between publish and "mark published" re-publishes, so
every consumer must be idempotent (example 04). ADR: `docs/adr/0009-transactional-outbox.md`.

## Regression test
* `flawedPublishInsideTxEmitsPhantomEventWhenCommitFails`: commit fails, broker still has the event.
* `flawedPublishAfterCommitLosesEventWhenBrokerIsDown`: order committed, no event, nothing remembers it.
* `outboxRollsBackEventTogetherWithOrder`: no order, no outbox row, nothing published.
* `outboxSurvivesBrokerOutageAndPublishesLater`: row stays pending during the outage, published once afterwards.
* `outboxIsAtLeastOnceNotExactlyOnce_consumersMustBeIdempotent`: documents the duplicate window honestly.

Docker-backed counterpart: `OutboxRelayKafkaTest` and `OutboxRelayResilienceTest` in
`services/platform-common/src/test/java/com/enterprise/oms/common/outbox/` (the Kafka one needs Docker/embedded broker;
skip behaviour is described in `docs/governance/test-strategy.md`).

## Review prompt to catch it
"If the commit fails after this send, what do consumers believe? If the send fails after this commit, who retries?"
