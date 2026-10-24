# 03 - `@Transactional` bypassed by self-invocation

Severity: **High** (silent loss of atomicity; the state change, history, and outbox row can diverge)

## PR title: "Refactor consumer: share handling logic"

```diff
 @Service
 public class InventoryEventConsumer {
     private final IdempotentEventProcessor processor;     // platform-common (verified)

-    @Transactional
-    public void onReserveInventory(EventEnvelope<ReserveInventory> env) {
-        processor.processOnce("inventory-service", env, () -> reserve(env.payload()));
-    }
+    public void onReserveInventory(EventEnvelope<ReserveInventory> env) {
+        handle(env);
+    }
+
+    @Transactional
+    void handle(EventEnvelope<ReserveInventory> env) {
+        processor.processOnce("inventory-service", env, () -> reserve(env.payload()));
+    }
 }
```

## Reasoning

Spring applies `@Transactional` through a proxy around the bean. A call from `onReserveInventory` to `handle` on `this` never crosses the proxy, so the annotation on `handle` is ignored (it is also package-private; proxies of non-public methods are not intercepted by default in all configurations). The code looks transactional and compiles; at runtime each repository call auto-commits on its own.

Consequences in this design:

1. `processed_events` insert commits immediately; then the stock update fails. On redelivery the event is seen as a duplicate and **skipped**, so the reservation is lost forever while the metric shows `oms.events.duplicates`. This breaks the central guarantee: the processed-event insert and the business effect share one transaction (contract section 7).
2. The outbox row (`InventoryReserved`) can commit while the reservation rolls back, or the reverse, recreating the dual-write problem the outbox is meant to solve.
3. `processOnce` may itself require an active transaction; depending on its propagation it either throws `IllegalTransactionStateException` or silently works non-atomically.

Where else this bites: `@Async`, `@Cacheable`, `@Retryable`, `@PreAuthorize` all share the proxy mechanism; the same self-call bypass applies to them.

## Fix options (safest first)

1. Put the annotation on the public entry point that the Kafka listener (an external caller) invokes.

```java
@Transactional
public void onReserveInventory(EventEnvelope<ReserveInventory> env) {
    processor.processOnce("inventory-service", env, () -> reserve(env.payload()));
}
```

2. If two entry points need different boundaries, move the transactional method to a separate bean (`ReservationHandler`) and inject it.
3. `TransactionTemplate` for explicit programmatic boundaries in code that must call itself.

Avoid `AopContext.currentProxy()` and AspectJ weaving as a first resort; they hide the problem and complicate tests.

## Regression test

Prove the transaction exists by forcing a failure after the first write and asserting nothing persisted.

```java
// sketch: @SpringBootTest, H2; SpyBean/MockBean on the collaborator that runs last
@Test
void failureInsideEffect_rollsBackProcessedEventRow() {
    var env = reserveEnvelope(UUID.randomUUID(), SKU_1001, 2);
    doThrow(new RuntimeException("boom")).when(outboxService).append(anyString(), anyString(), any());

    assertThatThrownBy(() -> consumer.onReserveInventory(env)).isInstanceOf(RuntimeException.class);

    assertThat(processedEventRepository.existsByConsumerAndEventId("inventory-service", env.eventId())).isFalse();
    assertThat(stockRow(SKU_1001).reserved()).isZero();
}

@Test
void consumerBeanIsTransactionalProxy() {
    assertThat(AopUtils.isAopProxy(consumer)).isTrue();   // guards against someone removing the annotation entirely
}
```

(Adjust the repository method name to the actual `ProcessedEventRepository`; it is on disk at `services/platform-common/.../idempotency/ProcessedEventRepository.java`.) The first test fails on the PR version because the `processed_events` row survives.

## Reviewer checklist from this scenario

- [ ] Is every `@Transactional` method called from outside the bean?
- [ ] Is there a test that fails mid-way and asserts rollback of *all* tables?
- [ ] Is the annotation on a public method of a Spring-managed bean?
