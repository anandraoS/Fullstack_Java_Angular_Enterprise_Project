# 09 Catching `Exception` and acknowledging

Code: `java/.../Ex09CatchAndAck.java`
Test: `Ex09CatchAndAckTest` (5 tests)

## Prompt that produces it
"Make the Kafka listener robust: it must never crash or get stuck on a bad message."

## Flawed code
```java
@KafkaListener(topics = "oms.inventory.events.v1")
public void onMessage(String json, Acknowledgment ack) {
    try {
        handler.handle(parse(json));
    } catch (Exception e) {
        log.error("error processing message", e);      // and carry on
    }
    ack.acknowledge();                                  // offset committed no matter what
}
```

## Why it is risky here
A transient DB blip becomes a permanently lost `InventoryReserved`: the order stays `VALIDATED` until the recovery job
(stuck-after 60s, 5 attempts) re-sends the command. A poison message is dropped with a log line nobody reads, with no
DLT record for operators (`oms.events.dead_lettered` stays 0, which looks healthy). The failure is invisible in metrics.

## Corrected approach
Let exceptions propagate to the container's `DefaultErrorHandler` (contract section 7): exponential backoff, 4 attempts
(500ms x2 up to 5s), then `DeadLetterPublishingRecoverer` -> `<topic>.DLT`; deserialization, validation and
`IllegalArgumentException` are non-retryable (`NonRetryableEventException`) and go straight to the DLT. Ack only after
the effect committed or the DLT write succeeded; if the DLT write fails the record is redelivered.
Relevant classes: `com.enterprise.oms.common.messaging.KafkaConsumerConfigSupport`, `NonRetryableEventException`,
`MessagingMetrics` (`oms.events.retries`, `oms.events.dead_lettered`).

## Regression test
Flawed: handler fails once with a transient error; effect never happened, offset committed, DLT empty. Fixed: transient
failure retried then acked; poison goes to DLT after exactly one attempt and does not block the next record; retries
exhausted -> DLT after 4 attempts; DLT unavailable -> offset NOT committed.

Real-component test: `ConsumerKafkaTest` in `services/platform-common/src/test/java/com/enterprise/oms/common/messaging/`.

## Review prompt to catch it
"Which exceptions are retryable? What is the exact line that decides the offset is committed?"
