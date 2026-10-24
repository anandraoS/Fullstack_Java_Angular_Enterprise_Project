# 05 - Offset acknowledged although processing failed

Severity: **High** (events silently dropped; saga stalls with no DLT record and no alert)

## PR title: "Make notification consumer resilient"

```diff
 @KafkaListener(topics = "oms.order.events.v1", containerFactory = "omsKafkaListenerContainerFactory")
 public void onOrderEvent(String payload, Acknowledgment ack) {
-    EventEnvelope<?> env = EventJson.parse(payload);
-    router.route(payload);
-    ack.acknowledge();
+    try {
+        router.route(TOPIC, payload);
+    } catch (Exception e) {
+        log.error("Failed to process event {}", payload, e);
+    } finally {
+        ack.acknowledge();
+    }
 }
```

## Reasoning

The author wanted the listener to "never crash". The effects:

1. **Silent data loss.** A transient failure (DB timeout) is logged and the offset is committed in `finally`. The event is never retried and never reaches `<topic>.DLT`. The customer never gets the notification; `oms.events.retries` and `oms.events.dead_lettered` stay at 0, so dashboards look healthy.
2. **The platform's error handling is bypassed.** `KafkaConsumerConfigSupport` (verified, platform-common) installs a `DefaultErrorHandler`: exponential backoff 500 ms x2 up to 5 s, 4 attempts, then `DeadLetterPublishingRecoverer` to `<topic>.DLT`, with non-retryable classes (`NonRetryableEventException`, `IllegalArgumentException`, deserialization, `ConstraintViolationException`...) skipping retries. That handler only works if the listener lets the exception propagate.
3. **Payload in logs.** `log.error("... {}", payload)` writes the whole event, including `customerEmail`, to logs (see 07).
4. Acknowledging in `finally` is wrong in principle: acknowledge means "this record is done", which is only true on success or after the recoverer has stored it in the DLT.

Which semantics do we actually want:

| Outcome | Correct behaviour |
|---|---|
| Success | ack (offset advances) |
| Duplicate event | success path (idempotent skip), ack, `oms.events.duplicates` +1 |
| Unknown eventType | ignored, ack, `oms.events.ignored` +1 |
| Transient failure | throw; container retries with backoff; `oms.events.retries` |
| Non-retryable (malformed/validation) | throw a non-retryable type; straight to DLT; `oms.events.dead_lettered` |
| Retries exhausted | recoverer publishes to DLT, then offset advances |

## Fix

```java
@KafkaListener(topics = "oms.order.events.v1", containerFactory = "omsKafkaListenerContainerFactory")
public void onOrderEvent(ConsumerRecord<String, String> record) {
    router.route(record);   // EventRouter (verified): parses (malformed -> MalformedEventException, non-retryable),
                            // dispatches by eventType, wraps the handler in IdempotentEventProcessor.processOnce,
                            // and lets handler exceptions propagate to the container's error handler
}
// ack mode is RECORD in the shared container factory: the offset is committed by the container
// after the listener returns normally, or after the error handler's recoverer succeeds.
```

If a handler must treat a business condition as permanent, throw `NonRetryableEventException` (verified class) with a message free of PII.

Never catch `Exception` in a listener without rethrowing. If you must catch (to add context), wrap and rethrow:

```java
catch (DataAccessException e) { throw new IllegalStateException("notification save failed eventId=" + env.eventId(), e); }
```

## Regression tests

Model: `ConsumerKafkaTest` (verified, EmbeddedKafka).

```java
// sketch
@Test
void transientFailure_isRetried_thenSucceeds() {
    doThrow(new TransientDataAccessResourceException("db")).doNothing().when(handler).handle(any());
    send(TOPIC, orderCreatedJson());
    Awaits.until(Duration.ofSeconds(10), () -> notificationRepository.count() == 1);
    assertThat(meter("oms.events.retries")).isGreaterThanOrEqualTo(1.0);
}

@Test
void alwaysFailing_endsInDlt_andOffsetAdvances() {
    doThrow(new TransientDataAccessResourceException("db")).when(handler).handle(any());
    send(TOPIC, orderCreatedJson());
    ConsumerRecord<String,String> dead = pollOne(TOPIC + ".DLT", Duration.ofSeconds(20));
    assertThat(header(dead, "x-original-topic")).isEqualTo(TOPIC);
    assertThat(header(dead, "x-exception-class")).isNotBlank();
    assertThat(meter("oms.events.dead_lettered")).isEqualTo(1.0);
}

@Test
void malformedJson_goesToDlt_withoutRetries() {
    send(TOPIC, "{not json");
    pollOne(TOPIC + ".DLT", Duration.ofSeconds(10));
    assertThat(meter("oms.events.retries")).isZero();
}
```

## Reviewer checklist from this scenario

- [ ] No catch-all in listeners without rethrow.
- [ ] Ack/offset handled by the container, not by `finally`.
- [ ] Tests for retry, DLT, and malformed input exist.
- [ ] `oms.events.dead_lettered` > 0 has an alert (runbook `../runbooks/02-kafka-consumer-lag.md`).
