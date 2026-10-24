# 02 `@Transactional` self-invocation

Code: `java/.../Ex02SelfInvocation.java` (JDK-proxy model of Spring's proxy rule)
Test: `Ex02SelfInvocationTest` (3 tests)

## Prompt that produces it
"Cancel all these orders, each in its own transaction."

## Flawed code
```java
@Service
class OrderCancellationService {
    public void cancelAll(List<UUID> ids) {
        for (UUID id : ids) {
            this.cancelOne(id);          // never goes through the Spring proxy
        }
    }

    @Transactional
    public void cancelOne(UUID id) { /* status change + history + outbox row */ }
}
```

## Why it is risky here
`@Transactional` is applied by a proxy that wraps the bean. `this.cancelOne(...)` calls the raw object, so no
transaction begins. In this system a state change writes `order_status_history`, `saga_step_log`, `audit_events` and an
outbox row that must commit together (contract section 6). Without the transaction (or with each repository call
auto-committing on its own), a failure halfway leaves a status change with no outbox event: the saga stalls and only
`SagaRecoveryJob`/reconciliation eventually notices. Same trap applies to `@Async`, `@Cacheable`, `@Retryable`, and
`@PreAuthorize` on self-called methods (the last one is a security bypass).

## Corrected code
```java
@Service
class OrderCancellationService {
    private final OneOrderCanceller one;       // separate bean, injected, therefore proxied
    public void cancelAll(List<UUID> ids) {
        for (UUID id : ids) {
            try { one.cancelOne(id); } catch (RuntimeException e) { /* record failure, continue */ }
        }
    }
}
@Service
class OneOrderCanceller {
    @Transactional
    public void cancelOne(UUID id) { ... }
}
```
Alternatives: `TransactionTemplate` inside the loop (explicit, no proxy), or inject self (works but hides the design
smell). Prefer the separate bean or `TransactionTemplate`.

## Regression test
`flawedSelfInvocationRunsWithoutTransaction` asserts the transaction manager log is empty and the callee saw
`tx=false`. The fixed tests assert one BEGIN/COMMIT pair per order and that a failing order produces ROLLBACK for that
order only, while its neighbours commit.

Spring-level version (open: no dedicated self-invocation test exists in the services; the nearest real tests are
`IdempotentProcessorAndAuditTest` in platform-common, which asserts that the processed-event marker rolls back on failure, and
`RetryingTransactions` use in order-service): call the public method, force a failure after the first write and
assert via the repository that the first write was rolled back. See `docs/known-limitations.md`.

## Review prompt to catch it
"Which bean instance receives this call: the proxy or `this`? Is the annotated method public and on a different bean?"
