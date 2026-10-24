# 10 Mutable shared state in a singleton

Code: `java/.../Ex10SingletonState.java`
Test: `Ex10SingletonStateTest` (2 tests)

## Prompt that produces it
"Create a pricing service that applies the customer's tier discount." (the assistant keeps the tier in a field to avoid
passing it around)

## Flawed code
```java
@Service
public class PricingService {
    private Tier currentTier;                       // one instance serves ALL request threads
    public void startQuote(Tier t) { this.currentTier = t; }
    public BigDecimal finishQuote(BigDecimal amount) { return amount.subtract(amount.multiply(currentTier.discount())); }
}
```
Same family: `SimpleDateFormat` as a static field, a `HashMap` cache mutated without synchronization, a `List` of
"pending items" on a controller, `MDC`/`ThreadLocal` values never cleared on pooled threads.

## Why it is risky here
Spring beans are singletons. Two concurrent requests interleave; the second overwrites the first's tier, so a STANDARD
customer can receive a GOLD discount or the reverse (a pricing bug and a cross-customer data leak). It reproduces only
under load, passes single-threaded tests, and looks like "flaky production".

## Corrected code
Make services stateless: pass `tier` and `amount` as parameters, keep per-request data in locals, use immutable value
objects, and `ConcurrentHashMap`/`Caffeine` for genuine shared caches. If a `ThreadLocal` is unavoidable, clear it in a
`finally` (the platform's `CorrelationIdFilter` does this for the MDC).

## Regression test
Flawed: two threads each call `startQuote` then wait at a barrier before `finishQuote`; asserts that NOT both got the
right price (one must be wrong because the field holds one value). Fixed: same interleaving, both prices correct.

## Review prompt to catch it
"Which non-final, non-local fields does this class have? Who writes them and who reads them, on which threads?"
