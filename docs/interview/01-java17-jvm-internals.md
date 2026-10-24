# Java 17 Language, Concurrency and JVM Internals

This chapter prepares you for the "core Java" half of a senior interview using the Order Management and Fulfillment platform as the running example. The code base targets Java 17 (`<maven.compiler.release>17</maven.compiler.release>` in `services/pom.xml`), so every language feature below is one you can legitimately claim to have used here. Java 21 features are mentioned only as "Java 21 note" boxes, because they are not available in this repository.

## Big picture and kid-friendly analogy

Imagine a busy school kitchen at lunch time.

* The **kitchen** is the JVM process. It has a fixed size (the container memory limit) and it cannot grow just because the cooks want more room.
* Each **cook** is a thread. Every cook has a small **cutting board** of their own (the thread stack). Things on your own board are private and fast.
* The big **prep tables in the middle** are the heap. Ingredients (objects) are put there so several cooks can share them. Fresh ingredients go on the "new stuff" table (young generation); things that stay a long time move to the "pantry shelf" (old generation).
* The **dishwasher** is the garbage collector. When the tables fill up it clears away anything nobody is using any more. A small dishwasher run is quick; a "stop everything and scrub the whole kitchen" run is a long pause.
* The **recipe book shelf** is metaspace: the descriptions of the dishes (classes) the kitchen knows how to make. It lives outside the prep tables, in the building itself (native memory).
* A cook who keeps making the same sandwich learns it by heart and gets faster. That is the **JIT compiler** turning hot bytecode into machine code.
* The **single stove burner** is a lock: only one cook at a time may use it, so the others queue. A **big whiteboard** that everybody can see is a `volatile` field: when one cook writes on it, the others see the change straight away instead of trusting their own memory of what was written earlier.

Mapping to the technical concepts:

| Kitchen | JVM concept | Interview hook |
|---|---|---|
| Kitchen size | Container limit, `-XX:MaxRAMPercentage` | Why pods get `OOMKilled` with a healthy heap |
| Cutting board | Thread stack (`-Xss`, default 1 MB on Linux x64) | Thread-per-request cost |
| Prep tables | Heap: young and old generations, G1 regions | Allocation rate, GC pauses |
| Dishwasher | G1 (default), ZGC (low pause) | Throughput vs latency trade-off |
| Recipe shelf | Metaspace, code cache | Native memory outside `-Xmx` |
| Cook memorising a recipe | Tiered JIT (interpreter, C1, C2) | Warm-up after a rolling deploy |
| Stove burner | `synchronized`, `ReentrantLock`, DB row lock | Lock ordering prevents deadlock |
| Whiteboard | `volatile`, happens-before | Visibility vs atomicity |

## Beginner tier

### Example 1: records for immutable data carriers

Every event, command and request body in this repository is a Java 17 `record`. A record gives you a final class, private final fields, a canonical constructor, accessors (`eventId()` not `getEventId()`), `equals`, `hashCode` and `toString` for free. The event envelope is generic and is also a record (`services/platform-common/src/main/java/com/enterprise/oms/common/events/EventEnvelope.java`):

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        UUID causationId,
        String aggregateType,
        String aggregateId,
        String producer,
        T payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
```

Request DTOs are records too, with Bean Validation annotations directly on the components (`services/order-service/src/main/java/com/enterprise/oms/order/api/dto/CreateOrderRequest.java`):

```java
public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid OrderItemRequest> items,
        @NotNull @Valid AddressDto shippingAddress) {
```

Interview points: records are **shallowly** immutable (a `List` component can still be mutated unless you copy it with `List.copyOf` in a compact constructor); they cannot extend another class but can implement interfaces; Jackson 2.12+ binds them through the canonical constructor; and they are a good fit for value objects and DTOs but a poor fit for JPA entities (entities need a no-arg constructor, mutable state and proxies, which is why `Stock`, `CustomerOrder` and `SagaInstance` are ordinary classes with `protected` no-arg constructors).

Java 17 also has **sealed classes** (`sealed ... permits ...`). This repository does not use them. A good interview answer is to show where they would fit: the `Decision` result of the order state machine is a record plus an enum `Kind`; a sealed interface with three record implementations (`Transition`, `NoOp`, `Invalid`) would let the compiler check that every caller handles all three. This is illustrative, not code from the repo:

```java
// illustrative only, not in the repository
sealed interface Decision permits Transition, NoOp, Invalid { }
record Transition(OrderStatus to) implements Decision { }
record NoOp(OrderStatus current) implements Decision { }
record Invalid() implements Decision { }
```

> **Java 21 note:** with sealed types, Java 21 finalises pattern matching for `switch` and record deconstruction patterns, so `switch (decision) { case Transition t -> ...; case NoOp n -> ...; case Invalid i -> ...; }` becomes exhaustive without a `default`. On Java 17 the same switch is only a preview feature, so this project keeps using enums and `if`/`switch` on `Kind`.

### Example 2: switch expressions and pattern matching for `instanceof`

Switch expressions (arrow form, yield a value, no fall-through) appear in the error mapping. `ErrorCode.forStatus` in `services/platform-common/src/main/java/com/enterprise/oms/common/error/ErrorCode.java`:

```java
public static ErrorCode forStatus(int status) {
    return switch (status) {
        case 400 -> VALIDATION_FAILED;
        case 401 -> UNAUTHENTICATED;
        case 403 -> FORBIDDEN;
        case 404 -> NOT_FOUND;
        case 405 -> METHOD_NOT_ALLOWED;
        case 406 -> NOT_ACCEPTABLE;
        case 409 -> CONFLICT;
        case 413 -> PAYLOAD_TOO_LARGE;
        case 415 -> UNSUPPORTED_MEDIA_TYPE;
        case 429 -> RATE_LIMITED;
        case 503 -> UPSTREAM_UNAVAILABLE;
        default -> status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
    };
}
```

Pattern matching for `instanceof` removes the cast-after-test boilerplate. In `services/platform-common/src/main/java/com/enterprise/oms/common/security/CurrentUser.java`:

```java
if (auth.getPrincipal() instanceof Jwt jwt) {
    id = jwt.getSubject();
    String c = jwt.getClaimAsString("customerId");
```

The binding variable `jwt` is in scope only where the test is definitely true. The same pattern is used in `RateLimitWebFilter` (`auth instanceof JwtAuthenticationToken jwt && auth.isAuthenticated()`) and `GlobalExceptionHandler` (`t instanceof PayloadTooLargeException p`).

### Example 3: text blocks and small Java 17 library additions

Text blocks make multi-line JSON readable in tests (`services/order-service/src/test/java/com/enterprise/oms/order/support/AbstractOrderSpringTest.java`):

```java
return """
        {"customerId":"%s","items":[{"productId":"%s","quantity":%d}],
         "shippingAddress":{"line1":"1 Test Street","city":"%s","postalCode":"10115","country":"DE"}}
        """.formatted(customerId, productId, quantity, city);
```

Incidental whitespace is stripped relative to the closing delimiter, and `String.formatted` (Java 15) is the instance version of `String.format`. Other Java 16/17 conveniences used in main code: `Stream.toList()` (an unmodifiable list, used everywhere in `OrderSagaOrchestrator`) and `java.util.HexFormat` (used in `IdempotencyKeys.fingerprint` to hex-encode the SHA-256 digest).

## Intermediate tier

### Example 1: `ConcurrentHashMap` plus a per-key lock (rate limiter)

`InMemoryOrderRateLimiter` (`services/order-service/src/main/java/com/enterprise/oms/order/ratelimit/InMemoryOrderRateLimiter.java`) is a token bucket per caller. The map is a `ConcurrentHashMap`; each bucket guards its own two fields with `synchronized`:

```java
@Override
public Decision tryAcquire(String key) {
    if (buckets.size() > EVICT_THRESHOLD) {
        evictIdle();
    }
    return buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoClock.getAsLong())).take();
}
...
synchronized Decision take() {
    refill(nanoClock.getAsLong());
    if (tokens >= 1.0) {
        tokens -= 1.0;
        return Decision.permit();
    }
    ...
}
```

Why this shape is correct, and what interviewers probe:

* `computeIfAbsent` is atomic per key, so two threads cannot create two buckets for the same caller. The mapping function must be short and must not touch the same map (that can deadlock or throw `IllegalStateException`).
* A bucket has two related fields (`tokens`, `lastNanos`). An `AtomicLong` protects one variable, not an invariant across two, so a small `synchronized` block is the right tool. Contention is per caller, not global.
* `System::nanoTime` (monotonic) is used instead of wall-clock time, and the clock is injectable (`LongSupplier`) so tests can control time. Wall-clock time can jump backwards.
* `buckets.size()` on a `ConcurrentHashMap` is an estimate under concurrent writes, which is fine for an eviction threshold. It is not fine for business decisions.
* The limit is per JVM, so with N replicas the effective limit is N times the capacity. The Javadoc says so, and the Redis implementation exists for a shared limit.

### Example 2: bounded `ExecutorService`, `CompletableFuture` and the thread-pool sizing trap

After an order commits, the first saga step (a REST call) must run off the request thread. `OrderServiceConfig` (`services/order-service/src/main/java/com/enterprise/oms/order/config/OrderServiceConfig.java`) defines the pool:

```java
@Bean(name = "omsSagaExecutor")
ThreadPoolTaskExecutor omsSagaExecutor(SagaProperties props) {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setThreadNamePrefix("saga-");
    ex.setCorePoolSize(props.getExecutorCoreSize());
    ex.setMaxPoolSize(props.getExecutorMaxSize());
    ex.setQueueCapacity(props.getExecutorQueueCapacity());
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    ex.setWaitForTasksToCompleteOnShutdown(true);
    ex.setAwaitTerminationSeconds(20);
    return ex;
}
```

With the defaults in `SagaProperties` (core 2, max 4, queue 200) the pool behaves like this, because `ThreadPoolExecutor` fills **core threads first, then the queue, and only then grows toward max**: only two threads run until 200 tasks are queued, and `maxPoolSize=4` is effectively unreachable under normal load. This is the classic sizing trap. If you want elasticity, use a small queue or a `SynchronousQueue`. The behaviour is deliberate here: the work is I/O bound and any overflow is recovered by `SagaRecoveryJob`, so a rejected task (`AbortPolicy` surfaces as `TaskRejectedException`, caught in `SagaTrigger`) is harmless. A pool with `AbortPolicy` is a bulkhead: validation traffic cannot consume unbounded threads.

`CompletableFuture` shows up at the Kafka boundary. In Spring Kafka 3.x `KafkaTemplate.send` returns a `CompletableFuture<SendResult>`, and the outbox relay blocks on it with a timeout (`services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxRelay.java`):

```java
private void send(OutboxEvent event) throws Exception {
    ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), null, event.getEventKey(),
            event.getPayload());
    for (Map.Entry<String, String> h : outboxService.headersOf(event).entrySet()) {
        record.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
    }
    kafka.send(record).get(props.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
}
```

Blocking is intentional: the relay must not mark a row published until the broker acknowledged it, and it must keep rows in order. Interview material around this: `get(timeout)` throws checked `InterruptedException`/`ExecutionException`/`TimeoutException` (the relay restores the interrupt flag on `InterruptedException`); `join()` throws unchecked `CompletionException`; `supplyAsync` without an executor argument runs on `ForkJoinPool.commonPool()`, which you must never use for blocking I/O; use `thenCompose` to chain dependent async calls and `thenCombine`/`allOf` to join independent ones; and `orTimeout`/`completeOnTimeout` (Java 9) give non-blocking timeouts.

### Example 3: `volatile`, atomics and safe publication

`ServiceTokenProvider` (`services/platform-common/src/main/java/com/enterprise/oms/common/security/ServiceTokenProvider.java`) caches a short-lived service JWT:

```java
private volatile String token;
private volatile Instant expiresAt = Instant.EPOCH;
...
public synchronized String token() {
    Instant now = Instant.now();
    if (token == null || now.isAfter(expiresAt.minus(RENEW_BEFORE))) {
        token = factory.issue(subject, List.of(Roles.SERVICE), null, ttl);
        expiresAt = now.plus(ttl);
    }
    return token;
}
```

The method is `synchronized`, which already gives mutual exclusion and visibility, so the `volatile` modifiers are belt and braces (they would matter only if some other code read the fields without the lock). Be ready to say that `volatile` guarantees visibility and ordering of a single variable, not atomicity of a compound check-then-act; the lock provides that. `OutboxService` uses a related idiom for a cached gauge value, `AtomicLong lastPending` plus `volatile long lastPendingAt`, where a benign race (two threads both refresh the count) is acceptable because the value is only a metric. Classes such as `MessagingMetrics` use `AtomicLong` counters, which use a CAS loop and need no lock. Under very high write contention `LongAdder` scales better, at the cost of a slower, non-atomic `sum()`.

## Advanced tier

### Example 1: container memory arithmetic and the JVM flags in the Dockerfile

Every service image sets the same options (`infrastructure/docker/order-service.Dockerfile`):

```dockerfile
ENV SERVER_PORT=${APP_PORT} \
    SPRING_PROFILES_ACTIVE=json-logs \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s \
    TZ=UTC \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Djava.io.tmpdir=/tmp -Djava.security.egd=file:/dev/urandom -Dfile.encoding=UTF-8"
```

and the Kubernetes Deployment (`infrastructure/kubernetes/base/order-service.yaml`) gives each pod:

```yaml
resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: "1"
    memory: 1Gi
```

Work the numbers: a 1 GiB limit with `MaxRAMPercentage=75` gives a maximum heap of about 768 MiB, and `InitialRAMPercentage=50` starts at about 512 MiB. That leaves about 256 MiB for everything that is **not** heap: metaspace (class metadata, unbounded unless `-XX:MaxMetaspaceSize` is set), the JIT code cache (up to 240 MB reserved with tiered compilation), thread stacks (about 1 MB each; Tomcat can run up to 100 worker threads via `OMS_TOMCAT_MAX_THREADS`, plus scheduler, Kafka and Hikari threads), direct buffers (Netty in the gateway) and GC data structures. If heap plus non-heap exceeds the limit the kernel OOM-killer ends the pod with exit code 137 (`OOMKilled`) even though no `OutOfMemoryError` was ever thrown. That is exactly the "container limit vs `MaxRAMPercentage` mismatch" branch in `docs/runbooks/08-jvm-memory.md`.

Detail worth knowing for interviews:

* `-XX:+UseContainerSupport` is on by default since JDK 10 (and JDK 17 understands cgroup v2), so the flag is redundant but harmless. Without container awareness the JVM would size the heap from the node's RAM.
* The explicit `-XX:+UseG1GC` is not decoration. JVM ergonomics classify a machine as "server class" only with at least 2 processors and about 1.8 GB of memory; with `cpu: "1"` and a 1 GiB limit the JVM would silently pick the Serial collector. Naming G1 overrides that.
* `-XX:+ExitOnOutOfMemoryError` makes the process die on the first `OutOfMemoryError` so Kubernetes restarts a clean JVM instead of leaving a half-dead one that still answers the liveness probe.
* The runbook recommends `MaxRAMPercentage=70`; the images ship `75.0`. Both are defensible, and the discrepancy is exactly the kind of thing to mention in an interview as "tune from measured live set, do not copy a number".

### Example 2: G1 versus ZGC, and diagnosing a real problem

G1 divides the heap into equal regions (1 to 32 MB), collects young regions with short stop-the-world pauses, and runs concurrent marking plus "mixed" collections for old regions. Objects larger than half a region are "humongous" and allocated directly in old space, which is why the runbook lists "humongous allocations (G1)" under GC symptoms: big JSON bodies and whole aggregates in events are the typical cause, and it is one reason request bodies are capped at 64 KB. ZGC (production-ready since JDK 15, single-generation in Java 17) targets sub-millisecond pauses independent of heap size, at the cost of extra memory and CPU. For a 768 MiB service heap G1 is the right default; ZGC pays off for multi-gigabyte heaps with strict tail latency.

> **Java 21 note:** generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) arrives in Java 21 and removes much of ZGC's throughput penalty. Not applicable to this Java 17 build.

The runbook's diagnostic sequence (`docs/runbooks/08-jvm-memory.md`, illustrative and not executed against a live cluster) is a good script to recite:

```bash
kubectl -n oms exec deploy/order-service -- jcmd 1 VM.flags
kubectl -n oms exec deploy/order-service -- jstat -gcutil 1 1000 5
kubectl -n oms exec deploy/order-service -- jcmd 1 GC.class_histogram | head -30
kubectl -n oms exec deploy/order-service -- jcmd 1 GC.heap_dump /tmp/heap.hprof
kubectl -n oms exec deploy/order-service -- jcmd 1 Thread.print | grep -c '^"'
```

How to read them: in `jstat -gcutil`, an `O` (old generation percent) pinned near 100 with the full-GC count (`FGC`) rising means memory pressure or a leak; a floor of live data after each major GC that creeps upward (`jvm.gc.live.data.size`) is a leak; a class histogram shows which type dominates; a thread dump (`jstack <pid>` or `jcmd <pid> Thread.print`) names deadlocks ("Found one Java-level deadlock") and shows pools stuck on the same monitor.

A gotcha that is **our analysis, not stated in the runbook**: the pod runs with `readOnlyRootFilesystem: true` and the only writable path is an `emptyDir` mounted at `/tmp` with `sizeLimit: 128Mi` (see the volumes section of `infrastructure/kubernetes/base/order-service.yaml`). A heap dump of a service with a few hundred megabytes of live data is larger than 128 MiB, so the runbook's `jcmd 1 GC.heap_dump /tmp/heap.hprof` could fill the volume and get the pod evicted. The runbook's own advice (`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps` on a mounted, access-controlled volume) is the safer production setup; the images do not currently set it. Heap dumps also contain customer data, so they are confidential artefacts.

### Example 3: JIT warm-up, class loading and ThreadLocal hygiene

**Tiered compilation.** Bytecode starts in the interpreter, hot methods are compiled by C1 (fast, lightly optimised), and the hottest by C2 (profile-guided, with inlining, escape analysis and speculative optimisations that can be de-optimised if an assumption breaks). Consequence for operations: a freshly started pod is slower than a warm one. This is why the Deployment uses `maxUnavailable: 0` with `maxSurge: 1`, a `startupProbe` allowing three minutes for the JVM plus Flyway, and a `preStop` sleep: new pods become ready before old ones leave, and traffic shifts gradually. Warm-up cost is also why a naive "scale to zero" or tiny-CPU limit hurts Java services: the JIT compiler threads compete for the same 1 CPU.

**Class loading.** The JDK has bootstrap, platform and application class loaders with parent-first delegation. Spring Boot's fat jar adds its own loader; the Dockerfile avoids it at runtime by splitting the jar into layers with `java -Djarmode=tools -jar app.jar extract --layers` (the Boot 3.3+ syntax) and copying `dependencies`, `spring-boot-loader`, `snapshot-dependencies` and `application` as separate image layers, so a code-only change rebuilds one small layer. Metaspace grows with loaded classes (Hibernate proxies, CGLIB proxies for `@Transactional` beans, Jackson serializers); a class-loader leak (typical in hot-reload dev tools) shows as metaspace growth that GC cannot reclaim.

**ThreadLocal in pooled threads.** MDC is a `ThreadLocal`, and the Kafka listener threads and the saga executor threads are reused, so forgetting to clear it leaks one request's correlation id into the next. The shared consumer infrastructure cleans up in a record interceptor (`services/platform-common/src/main/java/com/enterprise/oms/common/messaging/KafkaConsumerConfigSupport.java`):

```java
static final class CorrelationRecordInterceptor implements RecordInterceptor<String, String> {
    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                    Consumer<String, String> consumer) {
        Header h = record.headers().lastHeader(CorrelationIds.KAFKA_HEADER);
        String id = h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
        MDC.put(CorrelationIds.MDC_KEY, CorrelationIds.isValid(id) ? id : CorrelationIds.newId());
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove(CorrelationIds.MDC_KEY);
    }
}
```

`EventRouter.route` does the same with a `try/finally` that restores the previous value, and `OrderSagaOrchestrator.validate` follows the pattern again. The general rule: whoever sets a `ThreadLocal` on a pooled thread clears it in `finally`.

**Memory model in one paragraph.** Without a happens-before edge, one thread's write may never become visible to another or may be seen out of order. Edges are created by `synchronized` (unlock happens-before the next lock of the same monitor), `volatile` (write happens-before subsequent reads), `Thread.start`/`join`, `final` field semantics for safely constructed objects, and the `java.util.concurrent` classes (actions before submitting to an executor happen-before the task). `ReservationStrategies.active` in `services/inventory-service/src/main/java/com/enterprise/oms/inventory/reservation/ReservationStrategies.java` is a `volatile` reference to an immutable strategy object that tests and benchmarks swap at runtime: a textbook safe publication of one reference, with no lock needed because readers only need the latest reference.

**Locks, deadlocks and ordering.** The same discipline applies in the JVM and in the database. Inventory takes row locks in ascending `productId` order (`AtomicUpdateReservationStrategy`, ADR-0006) so two orders with opposite line orders cannot deadlock; with JVM locks the equivalent rule is a global lock-acquisition order (or `tryLock` with a timeout on `ReentrantLock`). In this repo database locks do the heavy lifting and JVM locks are limited to tiny critical sections, which is a deliberate design: a JVM lock does not protect you across multiple service instances.

> **Java 21 note:** virtual threads (`spring.threads.virtual.enabled=true` in Boot 3.2+) would let the blocking style used by `OutboxRelay` and the REST client scale without large pools, but pinned monitors (`synchronized` blocks around blocking calls) and connection-pool limits (Hikari max 10 to 20) remain the bottleneck. Not available on Java 17.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Records (events, DTOs, value types) | `services/platform-common/src/main/java/com/enterprise/oms/common/events/EventPayloads.java`, `services/platform-common/src/main/java/com/enterprise/oms/common/events/EventEnvelope.java`, `services/order-service/src/main/java/com/enterprise/oms/order/api/dto/CreateOrderRequest.java` | Canonical immutable carriers; entities stay classes |
| Switch expressions, pattern `instanceof` | `services/platform-common/src/main/java/com/enterprise/oms/common/error/ErrorCode.java`, `services/platform-common/src/main/java/com/enterprise/oms/common/security/CurrentUser.java`, `OrderSagaOrchestrator.recover` | Arrow `switch` statement used on `order.getStatus()` |
| Text blocks | `services/order-service/src/test/java/com/enterprise/oms/order/support/AbstractOrderSpringTest.java` | Tests only |
| Sealed classes | not used | Would fit `OrderStateMachine.Decision` |
| `ConcurrentHashMap` + per-key lock | `services/order-service/src/main/java/com/enterprise/oms/order/ratelimit/InMemoryOrderRateLimiter.java`, `gateway/src/main/java/com/enterprise/oms/gateway/ratelimit/InMemoryTokenBucketLimiter.java`, `services/inventory-service/src/main/java/com/enterprise/oms/inventory/cache/InMemoryProductCache.java` | All bounded (eviction or max entries) |
| Executor, rejection policy | `services/order-service/src/main/java/com/enterprise/oms/order/config/OrderServiceConfig.java`, `services/order-service/src/main/java/com/enterprise/oms/order/saga/SagaTrigger.java` | `AbortPolicy`, graceful shutdown 20 s |
| `CompletableFuture` | `services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxRelay.java` | Blocking `get(timeout)` on `KafkaTemplate.send` |
| `volatile`, atomics, `synchronized` | `ServiceTokenProvider`, `OutboxService`, `MessagingMetrics`, `ReservationStrategies` | Small, local critical sections |
| Container JVM flags | `infrastructure/docker/*.Dockerfile` | Same `JAVA_OPTS` in all five images |
| Memory limits, probes, preStop | `infrastructure/kubernetes/base/*.yaml` | 512Mi request, 1Gi limit |
| JVM diagnosis procedure | `docs/runbooks/08-jvm-memory.md` | Illustrative, not executed on a live cluster |
| Hikari sizing rationale | `services/order-service/src/main/resources/application.yml` | REST call happens outside any transaction |

### Demonstration-only vs production-grade

* **Production-grade habits**: bounded caches and buckets everywhere, explicit executor with a rejection policy, monotonic clocks injected for tests, `ThreadLocal` cleanup in `finally`, explicit GC choice, non-root user, `ExitOnOutOfMemoryError`, graceful shutdown that waits for the saga executor.
* **Demonstration-only or unverified**: the JVM flags were reasoned about, not tuned from a measured live set (`performance/` k6 profiles exist but no heap-floor trend assertion has been run); the runbook commands were never executed against a real cluster; there is no `-XX:+HeapDumpOnOutOfMemoryError` volume in the manifests; there is no JFR or async-profiler setup; the local developer machine runs a GraalVM JDK while images use Eclipse Temurin 17 (`eclipse-temurin:17.0.13_11-jre-alpine`, tag pinned but digest not resolved), and Alpine uses musl libc, which matters for native-memory behaviour and some profilers.
* **Testing honesty**: Docker is not installed on the dev machine, so Testcontainers-based tests (`@Testcontainers(disabledWithoutDocker = true)` in `services/platform-test-support/src/main/java/com/enterprise/oms/testsupport/AbstractPostgresIT.java`) are skipped there. Concurrency tests that matter for PostgreSQL row-lock behaviour run only on H2 in `MODE=PostgreSQL` locally.

## Common mistakes and gotchas

* **Treating `-Xmx` as the process size.** Heap is only part of the footprint; budget metaspace, code cache, thread stacks and direct buffers, or accept `OOMKilled`.
* **Shipping a thread pool whose `maxPoolSize` is never reached** because the queue is large (core, then queue, then max).
* **`ConcurrentHashMap` is not a license for check-then-act**: `if (!map.containsKey(k)) map.put(k, v)` is a race; use `computeIfAbsent`/`putIfAbsent`. `size()` is only an estimate under concurrency.
* **Calling `supplyAsync` without an executor for blocking work**, starving the common pool that parallel streams also use.
* **Assuming `volatile` makes `count++` atomic.** It does not; use `AtomicLong`/`LongAdder` or a lock.
* **Records with mutable components** (`List`, arrays) and records used as JPA entities.
* **Using wall-clock time (`System.currentTimeMillis`) for elapsed time or rate limiting.** Use `System.nanoTime`.
* **Forgetting `finally` for MDC/`ThreadLocal` cleanup** in pooled threads.
* **Swallowing `InterruptedException`.** Restore the flag with `Thread.currentThread().interrupt()`, as `OutboxRelay` does.
* **Believing Java 21 features exist in this build.** Virtual threads, final pattern matching for `switch`, record patterns and sequenced collections need Java 21.
* **Taking a heap dump into a tiny `emptyDir`** or leaving dumps with customer data on disk.

## Quick-recall cheat sheet

* Java 17 (LTS) features to name: records, sealed classes, text blocks, switch expressions, pattern matching for `instanceof`, `Stream.toList()`, `HexFormat`, helpful `NullPointerException` messages, strong encapsulation of JDK internals.
* Java 21 only: virtual threads, pattern matching for `switch`, record patterns, sequenced collections, generational ZGC.
* Records: final, shallowly immutable, accessors without `get`, great DTOs, bad entities.
* `ThreadPoolExecutor` order: core threads, then queue, then up to max, then reject.
* `CompletableFuture`: always pass an executor for blocking work; `get(timeout)` vs `join()`; `thenCompose` for chaining; `orTimeout` for deadlines.
* Visibility needs a happens-before edge: `synchronized`, `volatile`, `Thread.start/join`, `final`, `java.util.concurrent`.
* `AtomicLong` = CAS, single variable; `LongAdder` = contention-friendly counter; a lock protects multi-field invariants.
* Heap = young plus old; G1 regions and humongous objects; ZGC = low pause; metaspace, code cache, stacks, direct buffers are outside `-Xmx`.
* Container maths here: 1 GiB limit x 75 percent = about 768 MiB heap; roughly 256 MiB for non-heap.
* Explicit `-XX:+UseG1GC` avoids Serial GC on small containers; `ExitOnOutOfMemoryError` lets Kubernetes restart cleanly.
* Diagnostics: `jcmd <pid> VM.flags | GC.class_histogram | GC.heap_dump | Thread.print`, `jstat -gcutil`, `-Xlog:gc*`, Eclipse MAT dominator tree.
* Heap dumps are confidential and large: mount a proper volume.
* Same principle in JVM and DB: acquire locks in a global order to avoid deadlock.
* Clean up `ThreadLocal`/MDC in `finally` on pooled threads.
