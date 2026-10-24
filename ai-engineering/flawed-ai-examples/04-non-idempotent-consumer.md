# 04 Non-idempotent consumer

Code: `java/.../Ex04NonIdempotentConsumer.java`
Test: `Ex04NonIdempotentConsumerTest` (4 tests)

## Prompt that produces it
"Handle the InventoryReleased event by decreasing the reserved quantity."

## Flawed code
```java
@KafkaListener(topics = "oms.inventory.events.v1")
public void onEvent(String json) {
    var e = parse(json);
    stock.releaseReserved(e.productId(), e.quantity());   // runs on EVERY delivery
}
```

## Why it is risky here
Delivery is at-least-once by design: the outbox relay can re-publish, consumer rebalances re-deliver uncommitted
records, operators replay a DLT. A second delivery releases the same hold again; `reserved` drops below the truth and
another order's reservation is silently unbacked, so stock can be oversold even though the reservation SQL is correct.

## Corrected code
```java
@Transactional
public boolean processOnce(String consumer, EventEnvelope<?> env, Runnable effect) {
    if (processedRepo.existsById(new Key(consumer, env.eventId()))) { metrics.duplicate(...); return false; }
    processedRepo.saveAndFlush(new ProcessedEvent(consumer, env.eventId(), ...));   // unique (consumer, event_id)
    effect.run();                                    // same transaction: both commit or both roll back
    return true;
}
```
This is the real `com.enterprise.oms.common.idempotency.IdempotentEventProcessor`. Two concurrent deliveries both pass
`existsById`; the loser violates the primary key, rolls back and is retried, then sees the duplicate. The state machine
adds a second guard: applying an already-applied transition is a no-op (contract section 6). Honest claim:
effectively-once BUSINESS effect, not exactly-once delivery. ADR: `docs/adr/0005-idempotency-strategy.md`.

## Regression test
`flawedConsumerReleasesStockTwiceOnRedelivery` expects 5 reserved to remain and observes 0. The fixed tests assert a
single application for sequential and 8-way concurrent duplicates, and that a FAILED effect does not "burn" the event id
(the marker rolls back with the effect, so the retry still applies it).

Real-component test: `IdempotentProcessorAndAuditTest` in `services/platform-common/src/test/java/com/enterprise/oms/common/idempotency/`.

## Review prompt to catch it
"What happens if this exact event arrives twice, at the same time, and once more tomorrow from a DLT replay?"
