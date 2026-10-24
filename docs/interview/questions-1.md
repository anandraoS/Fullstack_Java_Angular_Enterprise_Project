# Interview questions Q1-Q48

Scope of this file: Java 17 and JVM, Spring Boot and Spring Cloud, REST API design, microservices and distributed systems, Kafka and event-driven design, and saga and eventual consistency, all grounded in the Order Management and Fulfillment demo platform in this repository. Source of truth for behaviour: `docs/architecture/00-shared-contract.md`, the ADRs in `docs/adr/`, the runbooks in `docs/runbooks/` and the service READMEs.

Honesty notes that apply to every answer: the HS256 JWT setup, replication factor 1, embedded KRaft Kafka and in-memory H2 are demonstration choices, not production-grade ones. There is no Eureka and no Spring Cloud Config Server: the gateway uses static route URIs and Kubernetes provides Services, ConfigMaps and Secrets. Docker is not installed on the authoring machine, so Testcontainers tests, Compose, Kubernetes, Terraform and monitoring files are statically validated only; the repository says so itself.

## Java 17 and JVM

### Q1. Which Java 17 language features does this codebase actually use, and which does it deliberately not use?
**Type:** Conceptual   **Level:** Beginner   **Topic:** Java 17 language features

**Short answer.** The services compile with `maven.compiler.release` 17 (`services/pom.xml`). They use records, pattern matching for `instanceof`, arrow-form `switch`, `var` and `Stream.toList()`. They do not use sealed classes, text blocks in main code, or anything from Java 21 such as virtual threads or record patterns.

**Detailed answer.** Records appear wherever data is immutable and transparent: `EventEnvelope<T>` and the payload types in `EventPayloads` (platform-common), `CurrentUser(String id, Set<String> roles, UUID customerId)` in `common/security`, and small private carriers such as `record ValidateRequest(...)` in `RestInventoryClient` and `ValidationInput` in `OrderSagaOrchestrator`. Pattern matching for `instanceof` removes casts, for example `auth.getPrincipal() instanceof Jwt jwt` in `CurrentUser.from` and `t instanceof PayloadTooLargeException p` in `GlobalExceptionHandler`. The arrow `switch` over `OrderStatus` in `OrderSagaOrchestrator.recover` makes the per-status re-drive table readable and, because enum arms are listed explicitly, easy to review. `Stream.toList()` (Java 16) returns an unmodifiable list and is used in `SagaRecoveryJob`.

What is not used matters in an interview. Sealed hierarchies are absent; the order lifecycle is an enum plus the pure `OrderStateMachine`, which is simpler for a table of `(status, trigger)` pairs. Entities such as `CustomerOrder` are ordinary classes because JPA needs a no-arg constructor and mutable state, so records are not a fit there. Virtual threads, `SequencedCollection` and pattern matching for `switch` are Java 21 features; the pinned toolchain is Java 17 (shared contract section 1), and the Docker image is `eclipse-temurin:17.0.13_11-jre-alpine`.

```xml
<java.version>17</java.version>
<maven.compiler.release>17</maven.compiler.release>
```

`--release 17` (rather than only `-source/-target`) also restricts the compiler to the Java 17 API surface, so a developer on a newer JDK cannot accidentally call a Java 21 method.

**Trade-offs and failure modes.**
- Records are shallowly immutable: a `List` component can still be mutated unless copied (`Map.copyOf` is used in `EventRouter` for exactly this reason).
- Jackson must be configured for records and tolerant reading; unknown-property failure would break forward compatibility of events.
- Staying on 17 means no virtual threads; blocking calls cost a platform thread each.

**Likely follow-ups.**
- Why not a sealed interface for order triggers? A flat enum with a lookup table is easier to test exhaustively; `OrderStateMachineTest` checks every pair.
- Is `Stream.toList()` the same as `collect(toList())`? No; it is unmodifiable and may allow nulls, whereas `Collectors.toList()` gives no guarantee about mutability.
- Does `release 17` change runtime behaviour? No, it only constrains compilation.

### Q2. Why are records a good fit for event envelopes and DTOs, and where do they cause trouble?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Records, Jackson and JPA

**Short answer.** Records give compact, immutable carriers with generated `equals`, `hashCode` and `toString`, which suits events that are written once and read many times. They are poor JPA entities, and their generated `toString` can leak data into logs.

**Detailed answer.** `EventEnvelope<T>` in `services/platform-common/.../common/events` holds `eventId, eventType, schemaVersion, occurredAt, correlationId, causationId, aggregateType, aggregateId, producer, payload`. Consumers first parse the envelope with a `JsonNode` payload (`EventJson.parseEnvelope`) and only bind the typed payload record inside the handler registered for that `eventType` (`EventRouter.Builder.on(type, Class, handler)`). This two-step parse is what lets an unknown `eventType` be ignored and counted instead of failing, and it lets a malformed record raise the non-retryable `MalformedEventException`.

Jackson (2.12+) supports records natively. `EventJson` configures a tolerant mapper (`FAIL_ON_UNKNOWN_PROPERTIES=false`, ISO dates, money keeps its scale), which is the technical basis of the tolerant-reader rule in ADR-0002. Compatibility consequence: a record component added by a newer producer is simply ignored by an old consumer; a component missing from an old event becomes `null` (or the primitive default), so new components should be reference types and treated as optional.

JPA is different: Hibernate needs a non-final class with a no-arg constructor and mutable fields for lazy proxies and dirty checking, so entities like `CustomerOrder`, `Stock` and `SagaInstance` are classes. API views (`OrderView`, `Dtos`) are separate from entities, which also prevents lazy-loading surprises and accidental exposure of fields.

Logging caution: a record's `toString` prints every component. `OrderCreated` carries `customerEmail`, so logging the payload record would violate the masking rule in contract section 10; code review scenario 07 (`docs/code-review/07-pii-in-logs.md`) exists for this reason and the platform uses `LogMasking.maskEmail`.

**Trade-offs and failure modes.**
- Records cannot extend classes and cannot be proxied, so they cannot be Spring beans needing AOP.
- A required-looking record component that is absent in an old event silently becomes `null`; handlers must null-check.
- Renaming a record component is a breaking wire change because the JSON property name changes with it.

**Likely follow-ups.**
- How do you evolve a record-based event? Add an optional component and bump `schemaVersion`; never rename (`docs/architecture/event-schema-evolution.md`).
- Can a record be a `@ConfigurationProperties` type? Yes with constructor binding, but this repo uses mutable property classes such as `SagaProperties`.
- Why `Map.copyOf` in `EventRouter`? To make the handler table immutable after build, which is thread-safe for concurrent listener threads.

### Q3. Scenario: Pods restart with exit code 137 although the heap graph looks healthy. What do you check?
**Type:** Scenario   **Level:** Intermediate   **Topic:** JVM memory in containers

**Short answer.** Exit code 137 means the container was killed (SIGKILL), usually by the kernel OOM killer because total process memory exceeded the container limit. A healthy heap points at non-heap memory (metaspace, thread stacks, direct buffers) or at a heap ceiling set too close to the limit.

**Detailed answer.** The order-service image sets `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC` (`infrastructure/docker/order-service.Dockerfile`), and the base Deployment (`infrastructure/kubernetes/base/order-service.yaml`) requests 512Mi and limits 1Gi. So the maximum heap is about 768 MiB, leaving roughly 256 MiB for metaspace, code cache, thread stacks, GC structures, Netty or NIO direct buffers and the OS. A service with many threads (Tomcat up to 100, a 4-thread scheduler, Kafka consumers, the saga executor) and several libraries can exceed that margin under load.

Runbook 08 (`docs/runbooks/08-jvm-memory.md`) gives the investigation order. First separate the two symptoms: `OOMKilled` (cgroup limit) versus `java.lang.OutOfMemoryError: Java heap space`. Compare `jvm_memory_used_bytes{area="heap"}` and `{area="nonheap"}` with `container_memory_working_set_bytes`. If the heap is flat but the working set climbs, look at thread count (`jvm_threads_live_threads`), metaspace and direct buffers. Capture evidence from one pod before restarting it: `jcmd 1 GC.class_histogram`, `jstat -gcutil`, a heap dump only when the heap is the suspect. Note that the JRE-only Alpine image may not contain `jcmd` or `jstat`; verify before an incident, or use an ephemeral debug container.

Honest gap: the Dockerfile has `ExitOnOutOfMemoryError` but not `-XX:+HeapDumpOnOutOfMemoryError`; the runbook recommends adding it with a restricted, mounted dump volume. Without it, a Java heap OOM exits with no dump. Memory limits in the manifests are starting values, not measured; the k6 soak profile (`performance/order-flow.js`, `PROFILE=soak`) has never been executed in this repository.

Fixes in order: bound the leak or the unbounded query (code review 06), then adjust `MaxRAMPercentage` and the limit together (for example 70 percent), then scale out.

**Trade-offs and failure modes.**
- Lowering `MaxRAMPercentage` reduces OOMKills but raises GC frequency.
- A heap dump pauses the JVM and contains customer data; store it encrypted and delete it.
- Restarting first destroys the evidence.

**Likely follow-ups.**
- Why not set `-Xmx`? Percentages follow the container limit when the manifest changes.
- Why G1? It is the default collector on server-class machines in 17 and gives a good balance of pause time and throughput; ZGC is an option for very large heaps but has not been evaluated for this workload.
- What does `ExitOnOutOfMemoryError` buy? A clean exit so Kubernetes restarts the pod instead of leaving a half-dead JVM.

### Q4. Scenario: A Kafka consumer keeps leaving the group and rebalancing. How do GC pauses and slow handlers cause this?
**Type:** Scenario   **Level:** Advanced   **Topic:** Consumer liveness, max.poll.interval.ms, GC

**Short answer.** A consumer is evicted when it does not call `poll()` within `max.poll.interval.ms` (Kafka default 300000 ms) or its heartbeat thread misses `session.timeout.ms`. Long GC pauses or slow handlers cause both; each eviction triggers a rebalance and redelivery.

**Detailed answer.** `KafkaConsumerConfigSupport.containerFactory` builds a `ConcurrentKafkaListenerContainerFactory` with `AckMode.RECORD`, `enable.auto.commit=false`, `auto.offset.reset=earliest` and concurrency from `oms.messaging.concurrency` (default 1). No `max.poll.*` property is overridden in the repository, so Kafka defaults apply. Records are processed one by one on the listener thread; between records the container calls `poll()`. A rebalance happens if the gap between two polls exceeds `max.poll.interval.ms`.

Two things can lengthen that gap. First, the retry backoff: `DefaultErrorHandler` sleeps on the listener thread. With `ExponentialBackOffWithMaxRetries(3)` and 500 ms doubling, a failing record sleeps 0.5 s, 1 s and 2 s (about 3.5 s) plus handler time per attempt; harmless alone, but it multiplies if every record in a batch fails. Second, a slow handler: a remote call or a lock wait inside the processing transaction (runbook 02 root-cause tree). Handlers here are deliberately short, DB-only, and one transaction per event.

GC pauses hit the heartbeat thread too: a stop-the-world pause longer than `session.timeout.ms` makes the broker declare the consumer dead. Runbook 08 lists the signals: `jvm_gc_pause_seconds_max`, pause fraction above 0.1, and consumer rebalances in logs (`rebalance`, `CommitFailed`, `max.poll`).

Consequence of a rebalance mid-record: the transaction may have committed but the offset not; the record is redelivered to another consumer and hits the unique `processed_events` key, so it is skipped and counted in `oms.events.duplicates` (`docs/architecture/delivery-semantics.md`, "Rebalance during processing"). Correctness survives; throughput suffers.

```java
// KafkaConsumerConfigSupport.errorHandler
ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, r.getMaxAttempts() - 1));
backOff.setInitialInterval(r.getInitialIntervalMs());
backOff.setMultiplier(r.getMultiplier());
backOff.setMaxInterval(r.getMaxIntervalMs());
```

**Trade-offs and failure modes.**
- Raising `max.poll.interval.ms` hides slow handlers and delays failure detection.
- Smaller `max.poll.records` shortens the work between polls but lowers batch efficiency.
- Blocking retries stall the whole partition; non-blocking retry topics (`@RetryableTopic`) are not used here.

**Likely follow-ups.**
- Which metric proves a rebalance storm? Repeated partition assignment log lines plus rising `oms.events.duplicates`.
- Does static membership help? It reduces rebalances on restarts; not configured here.
- Why not ack in `finally`? That loses events (code review 05).

### Q5. How are threads, connection pools and executors sized in order-service, and why does that matter for the JVM?
**Type:** Conceptual   **Level:** Advanced   **Topic:** Concurrency budget

**Short answer.** Request threads, scheduler threads, the saga executor, Kafka listener threads and Hikari connections are sized separately; the pool of database connections, not the number of threads, is the real limiter, so transactions are kept short and remote calls run outside them.

**Detailed answer.** Defaults from `services/order-service/src/main/resources/application.yml` and platform-common: Tomcat max threads 100 (`OMS_TOMCAT_MAX_THREADS`), Hikari maximum pool size 10 with `connection-timeout` 3000 ms, scheduler pool size 4 (`spring.task.scheduling.pool.size=4`, shared by `OutboxRelay`, `SagaRecoveryJob`, `ReconciliationService`, cleanup jobs), Kafka concurrency 1, and the saga executor `omsSagaExecutor` (`OrderServiceConfig`) with core 2, max 4, queue 200 and `AbortPolicy`. Inventory-service uses a pool of 20.

The design rule, written in the application.yml comment, is that a request holds a connection only for its transaction: `open-in-view` is false and `OrderSagaOrchestrator.validate` runs the REST call to inventory outside any transaction (read in a tx, call without tx, apply outcome in a tx). Otherwise 100 request threads could each hold a connection during a 3 s read timeout and exhaust a pool of 10.

`SagaTrigger` hands validation to the executor after commit (`@TransactionalEventListener(phase = AFTER_COMMIT)`), so `POST /orders` returns 202 without waiting. If the executor rejects the task (queue 200 full, `TaskRejectedException`), the order simply stays `CREATED` and `SagaRecoveryJob` picks it up; the design tolerates loss of the hand-off.

Failure signals: `hikaricp_connections_pending > 0` with `active == max`, `HikariPool ... Connection is not available, request timed out after 3000ms`, and thread dumps full of `HikariPool.getConnection` (runbook 04). The `OmsHikariPoolExhaustion` alert exists in `infrastructure/kubernetes/monitoring/prometheusrule.yaml`.

Sizing rule: `instances x pool size` must stay below PostgreSQL `max_connections` (inventory README section 6). With HPA up to 6 order-service replicas, that is 60 connections from this service alone.

**Trade-offs and failure modes.**
- A larger pool moves the queue into PostgreSQL rather than removing it.
- `AbortPolicy` makes saturation visible but relies on the recovery job for correctness.
- Scheduled jobs share 4 threads; a slow job can delay the outbox relay.

**Likely follow-ups.**
- Why fail fast at 3 s? Piled-up threads turn a DB blip into an outage; callers (saga, Kafka backoff) retry.
- Where would a bulkhead go? Nowhere yet; the repository has timeouts and a circuit breaker, not Resilience4j bulkheads.
- Why is open-in-view false? Lazy loading during view rendering would hold a connection for the whole request.

### Q6. Scenario: Heap usage creeps up over days. Which structures in this code base could leak, and how are they bounded?
**Type:** Scenario   **Level:** Advanced   **Topic:** Memory leaks and bounded caches

**Short answer.** The usual suspects are in-memory maps keyed by caller, caches without eviction, unbounded list queries and ThreadLocal state. This repository bounds the rate-limiter maps and the product cache and pages every list query, but several tables grow without a verified purge.

**Detailed answer.** Candidates and their controls:

- Rate limiter buckets: `InMemoryTokenBucketLimiter` (gateway) takes a `maxKeys`; when full it purges idle (full) buckets, and if still full, new keys share a bucket. `InMemoryOrderRateLimiter` (order-service) evicts idle full buckets once the map passes a threshold. Runbook 08 specifically flags the gateway buckets when `oms.redis.enabled=false`.
- Product cache: `InMemoryProductCache` is a bounded TTL map (`oms.inventory.cache.max-entries` 1000, `ttl-seconds` 30), and it caches only `GET /products/{id}`.
- Unbounded queries: contract paging (`size` 1..100) enforced by `PageRequestFactory`; the flawed alternative is documented in `docs/code-review/06-unbounded-pagination-n-plus-one.md`.
- MDC: `CorrelationRecordInterceptor` removes the key in `afterRecord`; `OrderSagaOrchestrator.validate` restores the previous MDC value in `finally`. Leaking MDC into pooled threads corrupts logs more than memory, but both are real.
- Database growth, not heap: `outbox_events` published rows are deleted after one hour by `OutboxRelay.cleanup`; `idempotency_keys` by `IdempotencyKeyCleanupJob` (24 h retention). I found no purge job for `processed_events`, `saga_step_log` or `audit_events` in the code; ADR-0005 and runbook 04 state that retention jobs are required, so treat that as an open item.

Method: watch `jvm_gc_live_data_size_bytes` (live set after major GC). A rising floor is a leak; a stable floor near max is undersizing (runbook 08). Capture `jcmd <pid> GC.class_histogram` twice, an hour apart, and diff; take a heap dump only if needed and open it in Eclipse MAT, reading the dominator tree.

```java
// InMemoryTokenBucketLimiter: bounded key space
if (!buckets.containsKey(key) && buckets.size() >= maxKeys) { /* purge idle buckets; if still full, share */ }
```

**Trade-offs and failure modes.**
- Sharing a bucket when full means unrelated callers can throttle each other under a key-flood attack; still safer than OOM.
- A short cache TTL limits staleness but increases DB reads.
- Heap dumps from production contain personal data.

**Likely follow-ups.**
- How do you prove a fix? A bounded-cache unit test (insert max+1, assert size at most max), listed as a pending regression test in runbook 08.
- Is Redis the cure for rate-limiter memory? It moves state out of the JVM, shared across replicas, with fallback to in-memory on error.
- What about direct buffers in the gateway? Netty uses them; compare working set versus heap.

### Q7. Why is this project on Java 17, and what would virtual threads on Java 21 change?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Java versions and virtual threads

**Short answer.** The pinned toolchain is Java 17 (a long-term-support release supported by Spring Boot 3.5), so virtual threads are unavailable. On Java 21, blocking servlet code could run on virtual threads, but the connection pool and downstream capacity would still cap throughput.

**Detailed answer.** Spring Boot 3.5.16 requires Java 17 or newer and supports Java 21 features such as `spring.threads.virtual.enabled=true`; this repository does not enable it because it compiles with `release 17` and runs `eclipse-temurin:17`. Be precise in an interview: virtual threads are final in Java 21 (JEP 444), not 17.

What would change on 21: order-service and inventory-service are classic servlet applications (Tomcat, blocking JDBC, blocking `RestClient`). A virtual thread per request removes the pressure of the 100-thread Tomcat cap for I/O-bound work. But the real limiters here are different: Hikari pool size (10 or 20), PostgreSQL row locks on hot `stock` rows, and Kafka partitions (3). Virtual threads queue more cheaply on a saturated pool; they do not create connections. Also `synchronized` blocks pinning carrier threads in older drivers, and `ThreadLocal`-based MDC, need review.

The gateway is a different story: it is reactive (Spring Cloud Gateway on Netty, `spring.main.web-application-type=reactive`), so it already multiplexes many connections on few threads and gains nothing from virtual threads.

The upgrade would be low risk structurally: Spring Boot 3.5 supports 21, the code uses no removed APIs, and the Docker base image tag is one line. The risk is in verification: concurrency tests (`ReservationConcurrencyTest`) and the benchmark would need re-running, and the repository has not been run on 21.

**Trade-offs and failure modes.**
- Virtual threads make unbounded fan-out easy; without a bulkhead or semaphore they can overwhelm a database.
- Mixed reactive and blocking code remains a mistake on any version.
- Staying on 17 maximises library certainty today but delays 21 features.

**Likely follow-ups.**
- Would you still need Hikari tuning on 21? Yes; the pool limit is independent of thread type.
- Does Kafka listener concurrency change? The container still uses platform threads unless configured otherwise; partitions remain the cap.
- Records, sealed classes or pattern `switch`? Records and `instanceof` patterns are in 17; pattern `switch` is 21.

### Q8. Scenario: A thread dump shows dozens of threads in `HikariPool.getConnection`. Walk through diagnosis and fix.
**Type:** Scenario   **Level:** Intermediate   **Topic:** Connection pool exhaustion

**Short answer.** Threads waiting for a connection mean the pool is exhausted. Decide whether connections are held too long (blocked or slow transactions, a remote call inside a transaction) or whether short transactions are simply too many for the pool, then fix the cause rather than only enlarging the pool.

**Detailed answer.** Take the thread dump first (`jcmd <pid> Thread.print | grep -c 'HikariPool.*getConnection'`, from `docs/mentoring/debugging.md` and runbook 04). Then use the decision rule in `docs/database/slow-query-lock-contention-pool-exhaustion.md`: if `hikaricp_connections_active == max` and `pending > 0`, look at `hikaricp_connections_usage_seconds`. High usage means connections are held: blocked on locks or slow statements. Low usage means many short transactions.

On PostgreSQL, inspect `pg_stat_activity` for `idle in transaction` sessions (a client holding locks and a connection while doing something else) and `pg_blocking_pids(pid)` chains. In this code base the classic hot spot is a queue on one `stock` row during a flash sale; the reservation path takes locks in a fixed order (reservation slot, then `stock` rows by ascending `product_id`), so contention shows as a queue on one SKU, not deadlocks.

The designed-in protections: connection timeout 3000 ms so callers fail fast; `open-in-view` false; the inventory REST call made outside the DB transaction; `leak-detection-threshold` off by default and 20000 ms in the `dev` profile of order-service. A reproduction recipe from runbook 04: set `spring.datasource.hikari.maximum-pool-size=2`, stub a 3 s inventory response and fire 10 parallel order creations.

Mitigations in order: capture evidence; cancel or terminate the blocking query (`pg_cancel_backend`, then `pg_terminate_backend` as a last resort); roll back a bad release; shed load by scaling consumers down temporarily or lowering gateway limits; add an index with `CREATE INDEX CONCURRENTLY`; set `idle_in_transaction_session_timeout`. Do not simply raise `OMS_DB_POOL_MAX`: it consumes PostgreSQL backends and adds waiters.

**Trade-offs and failure modes.**
- Fail-fast timeouts surface errors earlier but may cascade into saga retries.
- `lock_timeout` and `statement_timeout` protect the pool but abort legitimate long operations such as migrations; apply them to the application role only.
- H2 plans and lock behaviour differ, so diagnosing locks needs PostgreSQL.

**Likely follow-ups.**
- Little's law for sizing? Connections needed is roughly throughput times mean connection hold time.
- Why is a retry storm dangerous here? Retries multiply load on an already saturated pool.
- Which alert fires? `OmsHikariPoolExhaustion` in the PrometheusRule.

## Spring Boot and Spring Cloud

### Q9. How does the shared `platform-common` library wire itself into every service, and how can a service opt out of a piece?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Spring Boot auto-configuration

**Short answer.** `platform-common` has no `main` class. It registers eight `@AutoConfiguration` classes through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, each guarded by `@ConditionalOnClass`, `@ConditionalOnProperty` and `@ConditionalOnMissingBean`, plus an `EnvironmentPostProcessor` that supplies lowest-precedence defaults.

**Detailed answer.** The imports file lists `OmsCoreAutoConfiguration`, `OmsPersistenceAutoConfiguration`, `OmsKafkaAutoConfiguration`, `OmsOutboxAutoConfiguration`, `OmsMessagingAutoConfiguration`, `OmsAuditAutoConfiguration`, `OmsWebAutoConfiguration` and `JwtSecurityAutoConfiguration`. A service gets outbox, processed-event idempotency, audit, Problem Details error handling, correlation-id and size-limit filters, JWT resource server security and the `omsKafkaTemplate` and `omsKafkaListenerContainerFactory` beans simply by depending on the jar.

Conditions make each piece optional and overridable. For example `OmsOutboxAutoConfiguration` is `@ConditionalOnProperty(prefix = "oms.outbox", name = "enabled", matchIfMissing = true)`, and the relay bean additionally needs `@ConditionalOnBean(name = "omsKafkaTemplate")` and `oms.outbox.relay-enabled`. `omsKafkaTemplate` is `@ConditionalOnMissingBean(name = "omsKafkaTemplate")`, so a test can substitute its own. Notification-service sets `oms.outbox.enabled=false` because it only consumes. The security chain `omsSecurityFilterChain` is `@ConditionalOnMissingBean(SecurityFilterChain.class)`: declaring your own chain replaces it.

`OmsDefaultsEnvironmentPostProcessor` (registered in `META-INF/spring.factories`) adds `META-INF/oms-defaults.properties` as the last property source. That is how every service inherits Flyway locations `classpath:db/common,classpath:db/migration`, `open-in-view=false`, `ddl-auto=none`, graceful shutdown, no error detail leakage and actuator exposure, while any `application.yml` or environment variable still wins.

A subtle point from the README: entity and repository scanning for `outbox`, `idempotency` and `audit` packages is done via `@AutoConfigurationPackage`, so a service that adds its own `@EntityScan` must also list `com.enterprise.oms.common.{outbox,idempotency,audit}` or those tables stop being mapped.

```properties
# oms-defaults.properties (excerpt)
spring.jpa.open-in-view=false
spring.jpa.hibernate.ddl-auto=none
server.shutdown=graceful
spring.task.scheduling.pool.size=4
```

**Trade-offs and failure modes.**
- A shared library couples release cadence: changing it is a coordinated version bump (ADR-0001 rule).
- Hidden wiring surprises newcomers; the README and tests (`KafkaSupportTest`, `HelpersTest`) document it.
- A wrongly ordered auto-configuration can create beans before their dependencies; `OmsKafkaAutoConfiguration` uses `after = KafkaAutoConfiguration.class`.

**Likely follow-ups.**
- Why not component scanning? Auto-configuration lets consumers override with `@ConditionalOnMissingBean` and avoids scanning surprises.
- How does the gateway avoid all of this? It excludes the `Oms*` auto-configurations by name (see Q12).
- What does `matchIfMissing = true` mean? The feature is on unless explicitly disabled.

### Q10. Scenario: An event handler "works" in tests, yet in production a failed handler leaves a `processed_events` row and the effect is lost. What went wrong?
**Type:** Scenario   **Level:** Advanced   **Topic:** @Transactional proxy self-invocation

**Short answer.** The `@Transactional` annotation was on a method called through `this`, which bypasses the Spring proxy, so the processed-event insert and the business write no longer share a transaction. On redelivery the event looks like a duplicate and is skipped, so the effect is lost forever.

**Detailed answer.** This is code review scenario 03 (`docs/code-review/03-transactional-self-invocation.md`). Spring applies `@Transactional` with a proxy around the bean; only calls entering through the proxy start a transaction. A public listener method calls a package-private `@Transactional handle(...)` on `this`: the annotation is ignored (and non-public methods are not intercepted by default proxies anyway).

Why it is severe here: `IdempotentEventProcessor.processOnce` is annotated `@Transactional` and inserts `processed_events(consumer, event_id)` with `saveAndFlush`, then runs the effect. The central guarantee, "marker and effect commit together or not at all", depends on one surrounding transaction. If the marker commits and the stock update then fails, the container retries; the second delivery finds the marker (`repository.existsById`), logs "Duplicate event ignored", increments `oms.events.duplicates` and returns. The reservation is never made, and the only visible symptom is a duplicates metric.

The outbox has the same dependency: `OutboxService.append` uses `Propagation.MANDATORY`, so calling it without a transaction throws instead of silently writing outside one, which turns this mistake into a loud failure for the outbox half.

Prevention in this repo: handlers are registered as lambdas on `EventRouter` and executed inside `processOnce`, which is entered through the proxy of the `IdempotentEventProcessor` bean (the `EventRouter` holds the bean reference). The fix options, in order: put `@Transactional` on the public entry point invoked from outside; move the transactional method into another bean; use `TransactionTemplate` for programmatic boundaries. Orchestrator methods such as `onInventoryReserved` are `@Transactional` public methods called from the router lambda via the injected bean, so they go through the proxy.

Regression test: force a failure after the first write and assert that nothing persisted, including the `processed_events` row.

**Trade-offs and failure modes.**
- `AopContext.currentProxy()` or AspectJ weaving work but hide the problem and complicate tests.
- Same proxy limitation applies to `@Async`, `@Cacheable`, `@Retryable` and `@PreAuthorize`.
- A `private` or `final` method cannot be advised at all.

**Likely follow-ups.**
- How would you detect it in CI? A rollback test on H2 asserting zero rows after an injected exception.
- Does `MANDATORY` help consumers? It guarantees the outbox row joins the caller's transaction or the call fails.
- What if two beans need different boundaries? Separate beans, each with its own annotation.

### Q11. How does the gateway route to services without Eureka or a Config Server?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Spring Cloud Gateway routing and configuration

**Short answer.** Routes are a static table in `RoutesConfig` whose target URIs come from properties (`oms.gateway.routes.<svc>.uri`, environment `OMS_GATEWAY_ROUTES_<SVC>_URI`). There is no service discovery and no Spring Cloud Config Server; Kubernetes Services provide stable DNS names and ConfigMaps and Secrets provide configuration.

**Detailed answer.** `gateway/src/main/java/com/enterprise/oms/gateway/config/RoutesConfig.java` builds a `RouteLocator` with routes such as `order-service` (paths `/api/v1/customers/**`, `/api/v1/orders/**`, `/api/v1/admin/orders/**`, `/api/v1/admin/saga/**`, `/api/v1/admin/reconciliation/**`), `inventory-service`, `fulfillment-service` and `notification-service`, plus four `ops-*` routes that rewrite `/api/v1/ops/{svc}/**` to `/api/v1/ops/**` on the target. Every route is wrapped in a circuit breaker with a forward fallback (`/fallback/<svc>`) answered by `FallbackController` as 503 `UPSTREAM_UNAVAILABLE`. `spring.cloud.loadbalancer.enabled` is `false` in `gateway/src/main/resources/application.yml`, so no `lb://` URIs are used. (The shared contract still mentions `lb://` in one sentence; the implementation uses `http://` static URIs.)

Per environment the URIs differ: `http://localhost:18081` etc. by default, `http://order-service:18081` in `infrastructure/compose/docker-compose.yml` and in the Kubernetes ConfigMap `infrastructure/kubernetes/base/configmap.yaml`, where a Kubernetes `Service` of that name gives DNS and kube-proxy load balancing across ready pods. Secrets (JWT secret, database credentials, Redis password) come from `oms-secrets` through `secretKeyRef`; examples are in `infrastructure/kubernetes/examples`.

Why that is acceptable: with Kubernetes, service discovery and client-side load balancing are provided by the platform; Eureka would duplicate it. The cost is that the route table is code: adding a service means a new route and a deployment. `/api/v1/internal/**` is simply not routed; the gateway also answers it 404 before authentication.

Honest limits: Config Server features (central versioned config, dynamic refresh) are absent. Changing a ConfigMap needs a rollout; no `@RefreshScope` is used. Also no automatic retries at the gateway: the class comment says POST /orders and friends are not safely repeatable at the edge.

```java
.route("order-service", r -> r.path("/api/v1/customers", "/api/v1/customers/**",
        "/api/v1/orders", "/api/v1/orders/**", ...)
    .filters(breaker("order")).uri(order))
```

**Trade-offs and failure modes.**
- Static URIs cannot express canary or per-instance health; the Service and readiness probes do that job.
- A typo in a route property fails at startup or yields 503 at runtime, not at compile time.
- Kubernetes Service load balancing is connection-level; long-lived HTTP/1.1 connections can pin to one pod.

**Likely follow-ups.**
- Would you add Consul or Eureka? Only outside Kubernetes; inside, it is redundant.
- How do you do dynamic config? Reload via rollout, or add Spring Cloud Kubernetes or Config Server deliberately; neither is present.
- Why circuit breakers per route? To fail fast with a structured 503 when one upstream is down.

### Q12. Why is the gateway reactive, and how do you keep servlet, JPA and Kafka starters out of it?
**Type:** Conceptual   **Level:** Advanced   **Topic:** WebFlux gateway vs servlet services

**Short answer.** Spring Cloud Gateway runs on Netty and WebFlux, which suits a proxy that holds many mostly idle connections. It cannot coexist with the servlet-based `platform-common` auto-configurations, so the gateway excludes them explicitly and reuses only the JWT helper classes.

**Detailed answer.** `gateway/src/main/resources/application.yml` sets `spring.main.web-application-type: reactive` and excludes `OmsCoreAutoConfiguration` (its `ProblemWriter` needs the servlet API), `OmsPersistenceAutoConfiguration`, `OmsKafkaAutoConfiguration`, `OmsOutboxAutoConfiguration`, `OmsMessagingAutoConfiguration`, `OmsAuditAutoConfiguration`, `OmsWebAutoConfiguration` and `JwtSecurityAutoConfiguration`. It also sets `oms.security.enabled=false`, `oms.outbox.enabled=false`, `oms.messaging.enabled=false`, `oms.audit.enabled=false` and `oms.kafka.enabled=false`. The gateway needs only `JwtSecret`, `JwtTokenFactory`, `LogMasking` and `CorrelationIds`. The dependency declaration excludes `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-kafka`, Flyway and springdoc (platform-common README, "Gateway usage"). Listing the `Oms*` classes in `spring.autoconfigure.exclude` is required in addition to the Maven exclusions, as contract section 13 notes.

Edge behaviour is implemented as `WebFilter`s with explicit order constants (`EdgeFilters.Order`): correlation id (highest precedence), access log, inbound header sanitiser (strips `X-User-*`, `X-Roles`, `X-Forwarded-*` and similar), path guard (blocks `/api/v1/internal/**` and traversal), then Spring Security (order -100), then the rate limiter (-60, after authentication so it can key by JWT subject) and request size limit (-50). Timeouts: Netty connect 2000 ms, response timeout 10 s, Resilience4j time limiter 12 s as a backstop.

Reactive rules to remember: never block on the event loop (no JDBC, no `Thread.sleep`, no blocking `RestTemplate`); propagate context through the reactive chain (the rate limiter reads `ReactiveSecurityContextHolder`, not `SecurityContextHolder`); and MDC does not follow Reactor threads automatically, which is why `AccessLogWebFilter` uses `MDC.putCloseable` at log time.

**Trade-offs and failure modes.**
- Two programming models in one repository raise the learning curve; shared code must be servlet-free where the gateway uses it.
- A blocking call on the event loop stalls all requests on that thread.
- A forgotten exclusion shows up as a startup failure ("servlet API missing") rather than subtle behaviour, which is the desirable direction.

**Likely follow-ups.**
- Why not run the gateway as a servlet app? Spring Cloud Gateway MVC exists but the reactive variant was chosen; the filters here are WebFlux.
- Where is rate limiting keyed? By `sub:` for authenticated callers, `ip:` otherwise.
- Why strip `X-Forwarded-*`? So clients cannot spoof proxy or identity headers; backends authenticate from the bearer token only.

### Q13. Scenario: The circuit breaker on the inventory validation call opens. What do customers and operators see, and what recovers the orders?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Resilience4j retry and circuit breaker

**Short answer.** Order intake keeps returning 202 because validation is asynchronous. Orders stay `CREATED` while the breaker is open, the saga counts attempts, and `SagaRecoveryJob` re-validates after the dependency returns; after `oms.saga.max-attempts` (5) the order becomes `FAILED`.

**Detailed answer.** `RestInventoryClient.validate` is annotated `@Retry(name = "inventory")` and `@CircuitBreaker(name = "inventory")`. Configuration in `services/order-service/src/main/resources/application.yml`: retry 3 attempts, 200 ms, exponential multiplier 2, only for `InventoryUnavailableException`; circuit breaker count-based window 10, minimum 5 calls, failure threshold 50 percent, open for 10 s, 3 permitted half-open calls, automatic half-open transition. `InventoryCallException` (HTTP 4xx other than 408 and 429) is ignored by the breaker because it is a caller error, not an outage. Retry sits outside the breaker, so each attempt is counted by the breaker. Transport: connect 2 s and read 3 s from `OmsRestClientFactory`.

When it opens, calls fail immediately with `CallNotPermittedException`; `OrderSagaOrchestrator.validate` catches `RuntimeException` and calls `onValidationUnavailable`, which increments attempts, records `lastError` and logs a saga step `FAILED` while the order stays `CREATED`. If `attempts >= maxAttempts`, `exhaust` moves the order to `FAILED`. `SagaRecoveryJob` (every 10 s by default) re-drives sagas with no progress for 60 s: for `CREATED` it re-runs validation (`validateAfterCommit`).

Operators diagnose with `GET /api/v1/admin/saga/{orderId}` (`state`, `currentStep`, `attempts`, `lastError`), and `GET /api/v1/ops/health` for service readiness. The customer sees a `CREATED` order that does not progress; the UI polls with growing delay.

Unit and fake-server evidence exists: `InventoryClientResilienceTest` runs the real REST client against a fake HTTP server (retry, timeout, breaker, service token). Not verified: behaviour against a real inventory outage across multiple order-service replicas.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventory:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

**Trade-offs and failure modes.**
- Retry inside a short saga window multiplies load on a struggling dependency; the breaker limits that.
- Five attempts at 10 s spacing is about a minute of tolerance; a longer outage fails orders that a patient design could have kept.
- Breaker state is per instance, so replicas open independently.

**Likely follow-ups.**
- Why does validation run outside a transaction? To avoid holding a connection during network I/O.
- Is validation authoritative? No; it is advisory pricing and sanity check, and the reservation is the real stock decision.
- Why ignore 4xx? A bad request is not evidence of unavailability.

### Q14. How are configuration, profiles and secrets handled, and what fails fast on purpose?
**Type:** Conceptual   **Level:** Beginner   **Topic:** Spring profiles, externalised configuration, secrets

**Short answer.** Properties come from `application.yml` with environment-variable placeholders; profiles (`dev`, `local`, `prod`, `json-logs`) change behaviour. Secrets are never in the repository: the JWT secret and database credentials come from environment variables or Kubernetes Secrets, and services refuse to start without them in production.

**Detailed answer.** Order-service `application.yml` uses placeholders like `${OMS_DB_URL:jdbc:h2:mem:...}`, `${OMS_DB_POOL_MAX:10}` and `${OMS_SAGA_STUCK_AFTER_SECONDS:60}`, so the same jar runs locally on in-memory H2 and in Kubernetes on PostgreSQL. Profiles: `dev` (default) adds the seed Flyway location `classpath:db/seed` and leak detection; `prod` has no datasource defaults at all (inventory README: startup fails without them); `json-logs` switches logback to JSON for containers (`SPRING_PROFILES_ACTIVE=json-logs` in the Dockerfile).

Fail-fast rules: `OMS_JWT_SECRET` must be at least 32 bytes; `JwtSecret.resolve` fails startup when it is absent or short unless `oms.security.jwt.allow-ephemeral-secret=true`, which is for tests or a single-process run only, because services then cannot validate each other's tokens (`JwtSecretTest`). Gateway `dev` profile requires `OMS_DEMO_PASSWORD` (at least 8 characters) or it fails; there is no default password in code, and `scripts/run-local.sh` generates one per run. Tests generate their secrets at runtime.

In Kubernetes (`infrastructure/kubernetes/base/order-service.yaml`) non-secret settings come from `envFrom: configMapRef: oms-config`, and secrets from `secretKeyRef` entries of `oms-secrets` (`jwt-secret`, `order-db-username`, `order-db-password`, `redis-password`). The repository ships only examples (`secret.example.yaml`, `externalsecret.example.yaml`, `create-dev-secret.sh`); the AWS reference uses Secrets Manager with an External Secrets Operator. None of this has been applied to a real cluster.

**Trade-offs and failure modes.**
- Environment variables are visible to anyone who can read the pod spec or `/proc`; a secret manager with short-lived credentials is stronger.
- A fail-fast check delays discovery of a missing secret to deploy time; the startup probe (3 minutes) makes the failure visible as a crash loop.
- Relaxed binding means `OMS_DB_POOL_MAX` style names and property names can diverge; keep one naming convention.

**Likely follow-ups.**
- Where does the default H2 URL appear and why safe? Only in `application.yml` defaults for local runs; `prod` has no defaults.
- Can a secret appear in actuator output? `management.endpoint.health.show-details=never`, and `env` is not exposed (only health, info, prometheus).
- Why a separate `json-logs` profile? Plain console logs are easier locally.

### Q15. How is JWT security configured in each service, and what changes when you move from the demo HS256 setup to an OIDC provider?
**Type:** Conceptual   **Level:** Advanced   **Topic:** Spring Security resource server, OIDC

**Short answer.** Every service is a stateless OAuth2 resource server that re-validates the bearer JWT (signature, expiry, issuer, audience) and authorises with method security. Demo mode uses a shared HS256 secret; setting `oms.security.jwt.issuer-uri` or `jwk-set-uri` switches to RS256 via JWKS by configuration only.

**Detailed answer.** `JwtSecurityAutoConfiguration` builds a `NimbusJwtDecoder`. In HS256 mode: `NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.bytes(), "HmacSHA256"))` plus validators for `iss` (`oms-dev-issuer`), `aud` (`oms-api`) and time (30 s skew). In OIDC mode: `NimbusJwtDecoder.withJwkSetUri(...)` or `JwtDecoders.fromIssuerLocation(...)`, and the HS256 beans (`JwtTokenFactory`, `ServiceTokenProvider`) are not created. The `roles` claim becomes `ROLE_*` authorities (only CUSTOMER, OPERATOR, ADMIN, SERVICE). The default chain permits health, prometheus and OpenAPI paths, requires `SERVICE` for `/api/v1/internal/**`, and authenticates everything else; method security (`@PreAuthorize`) is on the controllers. CSRF is disabled deliberately for a cookie-less bearer API. 401 and 403 are problem+json via `ProblemSecurityHandlers`.

Honest weaknesses of the demo: HS256 means every service holds the signing secret, so any compromised service can mint tokens for any role. ADR-0010 states this is acceptable for a demonstration only. Tokens cannot be revoked before `exp` (3600 s from the dev endpoint); production would need short lifetimes plus introspection or a `jti` deny list. The Angular token lives in `sessionStorage`, readable by XSS; ADR-0010 recommends authorization code with PKCE through a BFF using HttpOnly cookies. Service-to-service calls use a short-lived SERVICE JWT minted from the same secret (`ServiceTokenProvider`); in OIDC mode a client-credentials flow would replace it, and the repository does not implement that.

Authorisation is layered: URL roles, method roles, then resource ownership via `CurrentUser.canAccessCustomer`, returning 404 for another customer's order. The gateway validates again but is defence in depth; backends never trust a header from the gateway (`InboundHeaderSanitizer` strips identity-style headers).

OIDC mode is configured but not verified: README section lists "OIDC/JWKS mode" as not verified.

**Trade-offs and failure modes.**
- Local JWT validation is fast and stateless but cannot see revocation.
- JWKS caching and key rotation behaviour comes from Nimbus defaults; test rotation before relying on it.
- `allow-ephemeral-secret` in a multi-instance environment breaks cross-service calls silently as 401s.

**Likely follow-ups.**
- Why 404 instead of 403 for others' orders? To prevent enumeration.
- Why re-validate behind a gateway? A misrouted network policy would otherwise become privilege escalation.
- How are key rotations handled for HS256? Coordinated restart, which is a reason to move to RS256.

### Q16. Scenario: A rolling deployment drops in-flight requests and loses queued work. What does the platform do, and what would you check?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Graceful shutdown and zero-downtime rollout

**Short answer.** Services enable graceful shutdown, Kubernetes sleeps in a `preStop` hook so endpoints drain before SIGTERM, and the Deployment uses `maxUnavailable: 0`. Anything lost in flight (a saga hand-off, an unpublished event) is recovered by design: outbox rows persist and `SagaRecoveryJob` re-drives stalled sagas.

**Detailed answer.** The pieces, from `infrastructure/kubernetes/base/order-service.yaml` and the Dockerfile: `terminationGracePeriodSeconds: 45` (greater than the 10 s `preStop` sleep plus the 25 s Spring graceful shutdown), `server.shutdown=graceful` with `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s`, `exec java ...` as PID 1 so SIGTERM reaches the JVM, rolling update `maxUnavailable: 0`, a PodDisruptionBudget with `minAvailable: 1`, topology spread constraints, a startup probe allowing 3 minutes (JVM plus Flyway), readiness at `/actuator/health/readiness` (includes `db` in the readiness group) and liveness at `/actuator/health/liveness`. The saga executor waits for running validations (`setWaitForTasksToCompleteOnShutdown(true)`, 20 s). On SIGTERM Spring stops accepting new requests, completes in-flight ones, stops Kafka listener containers (the current record finishes; with `AckMode.RECORD` its offset commits) and then closes pools.

What can still be lost, and why it does not matter: a 202 is returned only after the order, items, saga row, history and idempotency-key rows commit, so an acknowledged order is durable. A validation task queued in the executor but not started is lost on exit; the order stays `CREATED` and `SagaRecoveryJob` re-validates after `stuck-after-seconds`. An outbox row committed but unpublished is picked up by the relay on another pod or after restart. A consumer killed mid-record has not committed the offset or its transaction; redelivery processes it, and `processed_events` prevents a double effect.

Checks when it still hurts: readiness flapping (DB health timeout), ingress not honouring endpoint removal (the `preStop` sleep exists for this), a pool that cannot drain within 25 s, or a migration (Flyway) that locks tables during rollout (runbook 07 `docs/runbooks/07-failed-deployment.md` covers rollback). Reality check: manifests are statically validated; no rollout has been performed on a real cluster.

**Trade-offs and failure modes.**
- Long grace periods slow deployments.
- `maxUnavailable: 0` needs spare capacity (surge pods), which matters on constrained nodes.
- Two pods briefly running different code versions must tolerate each other's events (tolerant reader, additive changes).

**Likely follow-ups.**
- Why a `preStop` sleep? Endpoint removal and SIGTERM are not synchronised; sleeping avoids routing to a pod that is shutting down.
- What if Flyway fails on startup? The pod never becomes ready and the old pods keep serving; rollback via `kubectl rollout undo` (runbook 07).
- Does the relay stop cleanly? Scheduled threads end with the context; a half-sent row is simply re-sent.

## REST API design

### Q17. Why does `POST /api/v1/orders` return 202 Accepted instead of 201 Created, and how does a client learn the outcome?
**Type:** Conceptual   **Level:** Beginner   **Topic:** Asynchronous REST, status codes

**Short answer.** Creating an order starts a multi-step saga (validation, reservation, confirmation, fulfilment) that finishes later, so the API only promises that the request was accepted and durably recorded. It returns 202 with a `Location` header and the order in status `CREATED`; the client polls the resource.

**Detailed answer.** `OrderController.create` returns `ResponseEntity.accepted().location(URI.create("/api/v1/orders/" + id))` with an `OrderView` body. 201 would imply the resource is complete and usable; here the order has no prices (`totalAmount = 0.00`, item `sku/name/unitPrice` null) until the saga validates it (contract section 13). Insufficient stock appears seconds later as `REJECTED` with `statusReason`, not as a synchronous 409.

Why asynchronous: the platform has no distributed transaction (ADR-0004), and a synchronous request that waits for inventory, Kafka round trips and fulfilment would couple the response time to the slowest dependency and fail when any is down. Accepting and processing later keeps intake available even when inventory is down (see Q28).

Client protocol: read `Location`, then `GET /api/v1/orders/{id}` until a terminal status (`FULFILLED`, `REJECTED`, `CANCELLED`, `FAILED`). The Angular order detail screen polls with growing delay (1500 ms up to 8000 ms, `pollUntil`) and shows a timeline from `GET /api/v1/orders/{id}/history` (`docs/architecture/saga-and-consistency.md` section 6). Terminal states are final: a retry of a `FAILED` order is 409, and the customer places a new order.

Design points to mention: the `Idempotency-Key` header makes the POST safe to retry (Q18); `X-Correlation-Id` ties the order to logs across services; a webhook or server-sent event would be an alternative to polling, not implemented here. The 202 body is a snapshot: a replay of the same key returns the original 202 body, not the current state.

```http
HTTP/1.1 202 Accepted
Location: /api/v1/orders/7f9c1e2a-5b3d-4c8e-9a10-0d2f4b6c8e01
Idempotent-Replay: true   (only on a replay)
```

**Trade-offs and failure modes.**
- Polling adds load and latency; backoff and conditional requests reduce it.
- Clients must handle intermediate states honestly (the UI shows `COMPENSATION_PENDING`).
- A lost 202 response leaves the client unsure; the idempotency key lets it resend safely.

**Likely follow-ups.**
- When is 201 right? When the resource is fully created synchronously.
- Could you return the final state synchronously? Only by blocking on the saga, which ties availability to every dependency.
- Where is the status vocabulary documented? Contract section 6 and `OrderStatus`.

### Q18. How does the `Idempotency-Key` mechanism on `POST /orders` work end to end?
**Type:** Conceptual   **Level:** Advanced   **Topic:** API idempotency

**Short answer.** The header is mandatory (8 to 64 characters `[A-Za-z0-9_-]`). `OrderCommandService` looks up `(customerId scope, key)` in `idempotency_keys`; a match with the same request fingerprint replays the stored 202, a different fingerprint is 422 `IDEMPOTENCY_KEY_REUSED`, and a new key inserts the key row first so that concurrent duplicates serialise on a unique index.

**Detailed answer.** The flow in `create` and `doCreate`: validate the header (`IdempotencyKeys.require`), authorise the customer (a CUSTOMER token whose `customerId` differs from the body gets 403), reject duplicate product lines, compute a SHA-256 fingerprint of a canonical form (customerId, lines sorted by productId, trimmed address, so whitespace, property order and line order do not matter). Then one transaction: find an existing record; if present, `replayOf` compares hashes. If absent, `saveAndFlush` an `IdempotencyKeyRecord` first, create order, items, saga instance, history and audit rows, publish an `OrderAccepted` application event (handled after commit by `SagaTrigger`) and store the response snapshot on the key row.

```java
// OrderCommandService.create: loser of a race resolves as a replay
catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
    Optional<CreateResult> replay = tx.execute(status -> idempotencyKeys.findByScopeAndKey(scope, key)
            .map(r -> replayOf(r, hash)));
```

The scope is the body's `customerId`, so two customers using the same key string do not collide. Keys are retained for `oms.idempotency.retention` (24 h) and purged by `IdempotencyKeyCleanupJob`; a request that fails validation or authorisation does not consume its key (ADR-0005, contract section 13). A concurrent duplicate whose winner has not committed blocks on the unique index; the H2 URL sets `LOCK_TIMEOUT=10000` so it waits for the winner rather than failing after the 2 s default. If the stored record has no response body yet, `replayOf` throws a 409 "still being processed".

Why not rely on Kafka exactly-once: it does not protect an HTTP client retry or a database effect (ADR-0005). This layer is one of three: API key, consumer `processed_events`, state-machine idempotency.

Tests: `IdempotencyApiTest` (replay, mismatch, scope, 2 and 8 concurrent threads) on H2. Not verified: the same race on real PostgreSQL under multiple order-service replicas.

**Trade-offs and failure modes.**
- Keys expire after 24 h, after which the same key would create a new order.
- The snapshot replay returns stale state by design.
- Scope on body `customerId` means two staff members sharing a key for one customer share the record (documented as intended).

**Likely follow-ups.**
- Why insert the key row first? So a duplicate blocks on a unique constraint instead of both creating orders.
- Why 422 not 409 for key reuse? The request is well-formed but semantically unacceptable for that key; the contract fixes 422.
- Does the client need to generate keys per attempt? Per logical submission; the UI generates one in `order-create.ts` per submission.

### Q19. Scenario: Two identical `POST /orders` requests arrive within the same millisecond. How many orders exist afterwards, and how do you prove it?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Concurrency and idempotency

**Short answer.** Exactly one. Both requests try to insert the same `(scope, idem_key)` row; the database lets one commit, the other waits on the unique index, then fails or times out, re-reads the committed row and returns the stored 202 with `Idempotent-Replay: true`.

**Detailed answer.** Trace both requests through `OrderCommandService.create`. Each passes header validation and fingerprinting independently. In `doCreate` both do `findByScopeAndKey` and see nothing (read committed), then both `saveAndFlush` the key row. PostgreSQL (and H2) makes the second insert block on the unique index until the first transaction commits or rolls back. When the first commits, the second gets a unique violation, surfaced as `DataIntegrityViolationException` (or a lock timeout as `ConcurrencyFailureException`). The `create` loop catches both, opens a new transaction, loads the committed record and calls `replayOf`: same hash means the stored response is returned with `replay=true`, which the controller turns into the `Idempotent-Replay: true` header. If the winner rolled back instead, the loser's retry (up to `MAX_CREATE_ATTEMPTS = 3`) inserts the key itself.

Edge cases worth naming: the second request arrives with a different body but the same key and the winner has committed, which gives 422 `IDEMPOTENCY_KEY_REUSED`; the winner is slow and the loser times out (`LOCK_TIMEOUT` 10 s in the default H2 URL; on PostgreSQL `lock_timeout` applies if set); the winner committed the key row but crashed before storing the body (not possible, since the snapshot is stored in the same transaction).

Proving it: the repository's tests run two and eight threads against the same key and assert one order (`IdempotencyApiTest`, `OrderBddScenariosTest`). In an environment you would also query:

```sql
select count(*) from orders where customer_id = :c and created_at > :t;
select scope, idem_key, order_id, response_status from idempotency_keys where idem_key = :k;
```

and check gateway or order logs for the `Concurrent duplicate create resolved as replay` line (`log.info` in `create`). Honest gap: these tests run on H2; the real PostgreSQL variant of the race has not been executed on the dev machine.

**Trade-offs and failure modes.**
- Lock waits hold a connection and a Tomcat thread; the rate limiter (20 burst, 5 per second per caller) bounds abuse.
- A very long winner transaction makes duplicates time out and retry up to three times, then return 409 asking the client to retry with the same key.
- The dedupe window is bounded by retention.

**Likely follow-ups.**
- Why not a distributed lock? The unique constraint already serialises correctly and needs no extra infrastructure.
- Could the client see 409? Yes, if all three attempts fail without a committed winner.
- Does the Redis limiter change this? No; rate limiting and idempotency are independent.

### Q20. Scenario: Troubleshoot slow REST APIs
**Type:** Scenario   **Level:** Advanced   **Topic:** Latency diagnosis

**Short answer.** Start from user-visible latency by route (p95 and p99), find which hop owns the time using the correlation id and traces, then decide among four causes: database (slow query or locks), connection pool wait, a downstream call, or JVM pressure. Reproduce and measure before changing anything.

**Detailed answer.** Step 1, scope it. The gateway emits one access-log line per request with duration and route (`AccessLogWebFilter`), and `http.server.requests` is published with percentile histograms (`management.metrics.distribution.percentiles-histogram`). Query by `uri` and `service`:

```
histogram_quantile(0.99, sum by (le, service, uri) (rate(http_server_requests_seconds_bucket[5m])))
```

The `OmsHighP95Latency` alert exists in `infrastructure/kubernetes/monitoring/prometheusrule.yaml`. Is it one endpoint or all? One endpoint suggests a query or code path; all endpoints of one service suggests pool, GC or database.

Step 2, follow one request. Send `X-Correlation-Id`, then grep every service log for it (`docs/mentoring/debugging.md`); with tracing enabled (`OMS_TRACING_ENABLED`, sampling default 0.1, off locally) the spans show the slow hop. Remember gateway time includes the upstream; the gateway response timeout is 10 s.

Step 3, classify (runbook 04, `docs/database/slow-query-lock-contention-pool-exhaustion.md`):
- Pool wait: `hikaricp_connections_pending > 0`, `hikaricp_connections_acquire_seconds` high. Usage seconds high means held connections (locks, slow SQL, or a remote call inside a transaction; the code deliberately calls inventory outside transactions).
- Slow SQL: `pg_stat_statements` top by total time, `EXPLAIN (ANALYZE, BUFFERS)` on a replica. Order listings are served by composite indexes ending in `created_at` (`V12__indexes.sql`, for example `ix_orders_customer_created`). A `q` search is a prefix match; a `LIKE '%x%'` would not use `ix_...` indexes.
- N+1 or unbounded lists: statement counts; `default_batch_fetch_size: 50`, `@BatchSize(size = 50)` on `CustomerOrder` items and fetch-join queries in `OrderRepository`; paging max 100 (code review 06).
- Locks: `pg_blocking_pids`; a queue on a hot `stock` row.
- Downstream: breaker open or retries with 200 ms backoff and read timeout 3 s (worst case near 3 x 3 s plus backoff before the breaker intervenes).
- JVM: `jvm_gc_pause_seconds_max`, heap floor, thread count.
- Not slow but throttled: 429s with `Retry-After` and `X-RateLimit-Remaining` from the gateway.

Step 4, fix and verify: add an index with `CREATE INDEX CONCURRENTLY` after `EXPLAIN`, move the remote call out of the transaction, add a statement-count test, rerun the k6 `order-flow.js` profile. Honest status: the k6 scripts have never been executed here and the thresholds are initial targets, not measurements.

**Trade-offs and failure modes.**
- Percentile histograms increase metric cardinality; never label by order id.
- Sampled tracing can miss the very slow request; keep correlation ids in logs.
- Raising pool size or timeouts treats the symptom and often worsens it.

**Likely follow-ups.**
- First metric you open? The p99 by `uri` for the affected service.
- Why prefix search? Plain B-tree indexes serve prefixes, not infixes.
- How do you avoid regressions? Statement-count tests and index review with `EXPLAIN` on PostgreSQL (H2 plans differ).

### Q21. How is error handling standardised across services, and what must never appear in an error response?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** RFC 7807 problem details

**Short answer.** Every service answers errors as `application/problem+json` with a stable machine-readable `code`, a `correlationId`, a timestamp and an always-present `errors` array. `GlobalExceptionHandler` in `platform-common` maps exceptions to codes, and nothing internal (stack traces, SQL, exception class names) is exposed.

**Detailed answer.** The shape is fixed in contract section 5: `type`, `title`, `status`, `detail`, `instance`, `code` (UPPER_SNAKE), `correlationId`, `timestamp`, `errors[{field, message}]`. Codes include `VALIDATION_FAILED, NOT_FOUND, FORBIDDEN, UNAUTHENTICATED, CONFLICT, INVALID_STATE_TRANSITION, INSUFFICIENT_STOCK, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_REQUIRED, RATE_LIMITED, PAYLOAD_TOO_LARGE, UPSTREAM_UNAVAILABLE, INTERNAL_ERROR`, plus framework-raised `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE`, `BAD_REQUEST`. The `code`, not the human text, is the contract (`docs/architecture/api-versioning.md`).

Implementation: domain exceptions extend `OmsException` carrying an `ErrorCode` (`NotFoundException`, `ConflictException`, `InvalidStateTransitionException`, `IdempotencyKeyReusedException`, `RateLimitedException`, `UpstreamUnavailableException`, and so on). `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so Spring's own 400, 404, 405 and 415 are enriched with the same shape. `ProblemFactory` and `ProblemWriter` serve manual cases, and `ProblemSecurityHandlers` write 401 and 403 from the security filter chain, which is outside controller advice. The gateway has its own `Problems` and `ProblemErrorHandler` because it is reactive.

Leak prevention is layered: `oms-defaults.properties` sets `server.error.include-message=never`, `include-stacktrace=never`, `include-binding-errors=never` and `include-exception=false`; unexpected exceptions become 500 `INTERNAL_ERROR` without details; messages avoid personal data (code review 07); the dev token endpoint answers failures with a generic 401 and the UI never echoes `detail`. Also `INSUFFICIENT_STOCK` is not an HTTP result for orders: it surfaces asynchronously as a `REJECTED` order.

Details that interviewers like: 403 is evaluated before 400 for role rules (URL-level authorisation happens before request binding), 404 hides other customers' resources, and a 429 carries `Retry-After`.

```json
{ "type": "https://oms.example/problems/validation-failed", "status": 400,
  "code": "VALIDATION_FAILED", "correlationId": "...", "errors": [{"field": "items", "message": "..."}] }
```

**Trade-offs and failure modes.**
- A stable `code` set is itself an API surface; adding codes is non-breaking only if clients fall back on status.
- Over-detailed validation messages can leak schema hints.
- Errors raised before the servlet reaches controller advice (filters) need their own writers, as done for size limits and security.

**Likely follow-ups.**
- Why include `correlationId`? Support can find the request in logs without exposing internals.
- Why is `errors` always present? Clients can iterate without null checks.
- Where do 413s come from? `RequestSizeLimitFilter` (64 KB) at the service and `RequestSizeLimitWebFilter` at the gateway.

### Q22. Scenario: A customer can read another customer's order by guessing its UUID. How do you find and fix the flaw, and how does this repo prevent it?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Broken object level authorisation (BOLA)

**Short answer.** The endpoint only checked the caller's role, not ownership. The fix is a resource-level check against the authenticated identity, returning 404 (identical to "missing") for other customers' objects, and taking `customerId` from the token for customers, never trusting the body.

**Detailed answer.** The flawed pull request is `docs/code-review/02-bola-customerId-from-body.md`: `@PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")` passes for any customer; `GET /orders/{id}` loads by id without an owner check; `POST /orders` trusts `req.customerId()` from the JSON. UUIDs are not secrets: they appear in emails, logs and URLs.

The repository's implementation: `CurrentUser` (a record in `common/security`) exposes `canAccessCustomer(ownerCustomerId)`, true for staff or for a customer whose `customerId` claim equals the owner. `OrderCommandService.authorizeCustomer` returns 403 when a CUSTOMER's token `customerId` differs from the body (the id came from the caller, so 403 is honest). Reads go through `OrderQueryService.get(id, user)`; `OrderCommandService.cancel` filters with `.filter(o -> user.canAccessCustomer(o.getCustomerId()))` and otherwise throws `NotFoundException("Order", id)`, indistinguishable from a nonexistent id. Search forces the filter: a CUSTOMER calling `GET /orders?customerId=<other>` gets their own orders. A customer token without a `customerId` claim is refused on notification endpoints (403).

Layers: gateway role rules (defence in depth), URL rules, `@PreAuthorize`, then ownership. The gateway never injects identity headers and strips `X-User-*`, `X-Roles` and similar from inbound requests; backends read the bearer token only.

Test coverage: `OrderSecurityTest` and `SecurityApiTest` include privilege-escalation attempts and forged, expired and `alg=none` tokens; MockMvc cases cover the authorisation matrix. Not verified: the full matrix against real OIDC tokens.

Review checklist derived from the scenario: every `{id}` endpoint, every list with an owner filter, history, cancel, notifications and customers must apply the same check; write a test per endpoint that calls it with customer A's token on customer B's id and expects 404.

```java
// OrderCommandService.cancel
orders.findWithItemsById(orderId)
    .filter(o -> user.canAccessCustomer(o.getCustomerId()))
    .orElseThrow(() -> new NotFoundException("Order", orderId));
```

**Trade-offs and failure modes.**
- 404 for forbidden objects hides existence but can confuse legitimate support staff debugging; staff roles bypass it.
- Timing differences between "missing" and "forbidden" can still leak existence if the code paths differ; keep them the same.
- Ownership checks in controllers are easy to forget; central helpers and tests reduce that.

**Likely follow-ups.**
- Why 403 on POST but 404 on GET? Creating for another customer is an explicit caller error; reading another's order should look like absence.
- Is a UUID enough protection? No, never.
- How would row-level security help? PostgreSQL RLS could add a database-side check; not implemented here.

### Q23. How are pagination, sorting and filtering kept safe and fast?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Pagination, sort whitelists, indexes

**Short answer.** `PageRequestFactory` enforces `page >= 0`, `1 <= size <= 100` and a per-endpoint whitelist of sort fields, answering 400 `VALIDATION_FAILED` rather than silently clamping. Queries are parameterised and backed by composite indexes that end in `created_at`.

**Detailed answer.** `OrderController.search` declares `SORTABLE = Set.of("createdAt","updatedAt","status","totalAmount")` and `DEFAULT_SORT = createdAt desc`, validates `q` to at most 100 characters and calls `pages.create(page, size, sort, SORTABLE, DEFAULT_SORT)`. Size defaults to 20 and the maximum is 100; invalid values raise 400, because clamping hides client bugs and changes semantics. The response is the `PageResponse` envelope `{content, page, size, totalElements, totalPages}`. Sort is accepted as `field,dir` or repeated parameters, and only whitelisted API names reach Spring Data, so a client cannot sort on an arbitrary entity property (information leak, unindexed sort, or error).

Security and correctness: for CUSTOMER tokens the `customerId` filter is forced server-side; `q` is a prefix match on order id, customer email or shipping city, and LIKE wildcards are escaped in the inventory service; filters are bound parameters. Order lookups use `OrderSpecifications`.

Performance: migration `V12__indexes.sql` documents the design: every list is "newest first" with optional filters, so `ix_orders_customer_created (customer_id, created_at)`, `ix_orders_customer_status_created`, `ix_orders_status_created`, `ix_orders_created` and `ix_orders_updated` each serve a filter combination, and backward index scans satisfy `ORDER BY created_at DESC`. The failed-orders view is covered by the status index. N+1 is avoided by `hibernate.default_batch_fetch_size: 50`, `@BatchSize(size = 50)` on items, and fetch-join queries (`left join fetch o.items` in `OrderRepository`); `OrderRepositoryTest` asserts statement counts.

Known costs: offset pagination with a large `page` still scans and discards rows, and `totalElements` needs a count query; keyset pagination is the usual next step for deep scans and is not implemented. The flawed alternative, an unbounded `findAll()` with `size=1000000`, is dissected in `docs/code-review/06-unbounded-pagination-n-plus-one.md`. Index plans for inventory were captured on a real PostgreSQL 16.4 (`docs/database/inventory-explain-analyze.md`); order-service search plans are covered only by a Testcontainers test that was skipped without Docker.

**Trade-offs and failure modes.**
- Strict validation breaks sloppy clients early, which is intended.
- More indexes slow writes and consume memory.
- H2 plans differ from PostgreSQL; do not tune from H2.

**Likely follow-ups.**
- Offset versus keyset? Keyset scales to deep pages; offset is simpler and supports random page jumps.
- Why a sort whitelist? Prevent unindexed sorts and property probing.
- Why count queries matter? They can dominate list latency on big tables; make them optional if needed.

### Q24. Scenario: You must change the order API in a way that would break existing clients. How do you roll it out?
**Type:** Scenario   **Level:** Advanced   **Topic:** API versioning and deprecation

**Short answer.** Stay additive inside `/api/v1` where possible; if the change is truly breaking, introduce `/api/v2` alongside v1, deprecate v1 with `Deprecation` and `Sunset` headers, give at least 90 days to external clients, and remove v1 only when traffic is zero or the sunset date passes.

**Detailed answer.** ADR-0007 chose URL-path versioning (visible in logs, routes, rate-limit rules and curl) over media-type or query versioning. `docs/architecture/api-versioning.md` lists what is allowed inside v1: new optional request fields with defaults, new response fields, new endpoints, new optional query parameters, new error codes (clients fall back on status), loosened validation, and new values only for enums documented as open (`channel`, `tier`, error `code`). Breaking: removing or renaming fields, changing type, scale or units (money is scale 2), tightening validation, new required fields, a new role requirement, changing 403 to 404, changing page defaults. Order and saga status enums are closed per major version.

Rollout for a rename such as `shippingAddress.line1`: first add the new field alongside the old (additive release), update OpenAPI (`deprecated: true`) and release notes, start returning `Deprecation: true` and, once a date is fixed, `Sunset: <HTTP-date>` plus a `Link` header; the gateway can add them per route without service changes. Track v1 usage per route in gateway metrics. Publish `/api/v2` routes in `RoutesConfig` (new path patterns to the same or new service), keep both live, and retire v1 after the sunset.

Client tolerance is the other half: the Angular models read only the fields they use, and the guide says clients must ignore unknown fields. The same tolerant-reader principle applies to events, which are versioned differently (topic suffix plus `schemaVersion`, ADR-0002).

Enforcement, honestly: the guide describes an OpenAPI diff in CI that fails on breaking changes. Looking at `.github/workflows/ci.yml`, I did not find such a job (jobs are version, build-test, integration, frontend, codeql, semgrep, dependency-scan, secret-scan, infra-validate, docker, smoke and deploy). The committed exports in `specs/openapi/*.json` are generated by `scripts/export-openapi.mjs`; the diff gate is a design intention, not an implemented control. Contract tests per service assert status, headers and shape of documented examples.

**Trade-offs and failure modes.**
- Two live versions double test and support surface.
- Version in the URL means the resource identity changes, which purists dislike.
- Silent semantic changes (same shape, different meaning) are the most dangerous and are listed as breaking.

**Likely follow-ups.**
- Why not header versioning? Harder to see in logs and browsers; fragile gateway routing.
- How do you tell who still uses v1? Gateway metrics by route and client or subject.
- Can a security fix break compatibility? Yes, with notice; the guide allows overrides.

## Microservices and distributed systems

### Q25. Scenario: Design a high-traffic order platform
**Type:** Scenario   **Level:** Advanced   **Topic:** System design and capacity planning

**Short answer.** Keep intake asynchronous and cheap (accept, persist, acknowledge), push work through Kafka-driven sagas, protect the single correctness hot spot (stock rows) with atomic SQL, and scale stateless tiers horizontally while sizing the real limiters: PostgreSQL write capacity, connection budget, Kafka partitions and hot SKUs. Numbers below are illustrative planning arithmetic, not measurements of this repository.

**Detailed answer.** Assume 5 million orders per day, a 10x peak-to-average factor and 20 reads per order. Average is about 58 orders per second, peak about 600 orders per second and 12,000 reads per second. Reads (catalogue, order status polling) dominate request count; writes dominate cost.

Architecture (this repo's shape, scaled): Angular behind CDN, an ALB, `api-gateway` replicas (stateless, JWT validation, per-subject rate limits, circuit breakers), the four services behind Kubernetes Services, PostgreSQL per service, Kafka between them, optional Redis (`infrastructure/cloud/README.md` AWS reference: EKS, RDS Multi-AZ, MSK, ElastiCache; nothing applied).

Per-order work at peak: order-service write (order, items, saga, history, audit, idempotency: about 6 to 8 rows), one validation REST call, outbox rows (`OrderCreated`, `ReserveInventory`, `OrderConfirmed`, `StartFulfillment`), inventory reservation (slot, items, stock updates, movement, outbox, audit), fulfilment and notification rows. Roughly 40 row writes and 10 Kafka messages per order across services, so 600 orders per second means about 24,000 row writes per second and 6,000 messages per second cluster-wide. Spread over four databases that is feasible on managed PostgreSQL with sensible instance sizes, but not on one small instance.

Bottlenecks, in the order they bite:
1. Hot SKU rows: the benchmark in the inventory README (laptop, PostgreSQL 16.4) shows one hot row serialising at roughly 130 reservations per second for the atomic strategy, whereas spread across 200 products it reached about 800. Mitigation: split stock into buckets per SKU, reserve from a random bucket, or queue per hot SKU (the README names this as a later option).
2. Kafka partitions: 3 per topic caps consumer parallelism per group at 3 consumers. Choose 12 to 24 partitions before launch; raising later reshuffles keys and breaks per-order ordering during migration (ADR-0002).
3. DB connection budget: `replicas x pool size` below `max_connections`; add PgBouncer (not present), and size pools by transaction time (Little's law), not threads.
4. Outbox relay: polling every 500 ms in batches of 50 gives at most about 100 events per second per instance (50 per 500 ms) when saturated; raise batch size and poll frequency, claim rows with `FOR UPDATE SKIP LOCKED`, or move to CDC (Debezium), as ADR-0009 describes.
5. Gateway: in-memory rate limits are per replica; enable Redis (`oms.redis.enabled`).
6. Reads: product cache (Redis), read replicas for catalogue and order search (accept replication lag), CDN for static assets.

Scaling plan: HPA on CPU (2 to 6 replicas in `infrastructure/kubernetes/base`), KEDA lag scaling as an example manifest (`infrastructure/kubernetes/keda`, not in any overlay), topic partitions sized to the target consumer count, scheduled jobs guarded (ShedLock is not present today; the order-service README says to add a lock or row claiming before running many replicas).

Resilience: async accept shields users from downstream outages; circuit breaker plus recovery job; DLTs and alerts; reconciliation. Observability: RED metrics, lag, outbox pending, saga active; k6 `order-flow.js` and `oversell.js` (never executed here) to find the knee.

**Trade-offs and failure modes.**
- Eventual consistency everywhere; users see intermediate states.
- More partitions raise broker overhead and rebalance time.
- Bucketed stock complicates "available" reporting and reservation release.
- Cost: four databases, Kafka and Redis cost more than a modular monolith for modest traffic (ADR-0001).

**Likely follow-ups.**
- First thing you would load-test? `oversell.js` on PostgreSQL for correctness, then `order-flow.js` stress to find the knee.
- Why not synchronous validation and reservation? Availability and latency coupling.
- When is the monolith better? Below the traffic and team size where independent scaling matters (ADR-0001 merge-back criteria).

### Q26. Why four services plus a gateway rather than a modular monolith, and when would you merge them back?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Service boundaries

**Short answer.** The repository chooses services to demonstrate real distributed failure modes and independent scaling; ADR-0001 admits a team of this size would normally start with a modular monolith. Boundaries follow data ownership and failure isolation, and the merge-back criteria are written down.

**Detailed answer.** ADR-0001 lists decision drivers: independent deployability and failure isolation for notification and fulfilment (a notification outage must never block order acceptance), runtime-enforced data ownership (ADR-0004), the goal of demonstrating eventual consistency, saga and delivery semantics honestly, and keeping the stack runnable without Docker (embedded Kafka, H2).

The boundary logic: order-service owns customers, orders and the saga; inventory-service owns products, stock and reservations (hot reads and writes); fulfilment simulates picking and shipping; notification consumes events only. Customer data sits with orders and product data with stock because they change together. Alternatives rejected: many fine-grained services (more hops and sagas without business reason).

Rules that prevent a distributed monolith: no synchronous call chain deeper than one hop (the only sync call is validation, with circuit breaker, retry and timeouts); everything else travels as events via the outbox; no cross-service database access; `platform-common` carries only cross-cutting infrastructure and is versioned as a unit (`1.0.0`).

Merge-back criteria (ADR-0001): one team owns all four; no service has ever scaled independently; more than half of releases touch two or more services (lock-step deploys); the saga adds more incidents than it prevents; infrastructure cost or on-call burden dominates. Mechanically the merge is cheap if boundaries held: each service owns its tables, so modules can keep separate schemas inside one deployable.

Costs acknowledged: four databases, Kafka, gateway, mandatory tracing, slower end-to-end tests, duplicated boilerplate (mitigated by the library), harder local debugging.

Honest check on the claim: some of the independence is theoretical here: all services share one library version, one repository and one CI pipeline, and deployments have not been exercised on real infrastructure. The "KEDA scaling" mentioned in ADR-0001 is an example manifest, not enabled in overlays.

**Trade-offs and failure modes.**
- Distributed transactions disappear, replaced by sagas and idempotency work.
- Version skew between services requires tolerant readers and additive changes.
- The gateway is a single logical point of failure; run replicas.

**Likely follow-ups.**
- How do you keep boundaries honest? Separate schemas and credentials, no shared tables, architecture review, and contract tests.
- Would you split notification first? It is already event-only, so yes, it is the cheapest to extract.
- What breaks in a merge? Event-based calls would become method calls; the outbox may remain for external integrations.

### Q27. How is data ownership enforced, and how do services get data they do not own without cross-database joins?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Database per service

**Short answer.** Each service has its own database and credentials (`order_db`, `inventory_db`, `fulfillment_db`, `notification_db`), and data from other services arrives only via event payloads, one synchronous validate call, or local denormalised snapshots.

**Detailed answer.** ADR-0004 decides: one PostgreSQL instance in Compose and Kubernetes with a separate database and user per service, created by `infrastructure/compose/postgres/init/01-create-databases.sh`; locally each service uses its own in-memory H2 in `MODE=PostgreSQL` running the same Flyway SQL. Flyway ranges are partitioned: V1 to V8 reserved for platform-common (`outbox_events`, `processed_events`, `audit_events` in `classpath:db/common`), service schemas from V10, seed data from V1000 in the `dev` profile.

How data crosses boundaries: (a) event payloads carry what the consumer needs, for example `customerEmail` in `OrderCreated` so notification never queries order; (b) the single synchronous `POST /api/v1/internal/products/validate`; (c) local copies, such as the price snapshot (`sku`, `name`, `unitPrice`) in `order_items` and `order_contacts(order_id, customer_id, customer_email)` in notification, fed from every order event because fulfilment events carry only `orderId`.

Consequences to discuss: no foreign keys across services, so referential integrity is eventual and checked by `ReconciliationService`; reporting needs events or read models; reference data is duplicated; a shared PostgreSQL instance remains a shared failure and resource domain (noisy neighbour), as the cloud README admits, and splitting later is changing JDBC URLs. H2 `MODE=PostgreSQL` is an approximation: the code avoids `ON CONFLICT` and PostgreSQL-only types so both run the same SQL (`ReservationClaims` uses a plain INSERT in a savepoint for that reason), and real PostgreSQL behaviour is verified by Testcontainers tests that skip without Docker, plus a Maven-cached PostgreSQL 16.4 binary used for the inventory evidence.

Timing issue to know: a fulfilment event for an order whose contact is not yet known is retried by the container and eventually dead-lettered, because consumers across topics are not ordered relative to each other.

**Trade-offs and failure modes.**
- Denormalised copies can be stale; they are snapshots by design (price at order time).
- Cross-topic ordering is not guaranteed, so consumers must tolerate arriving early.
- Backup and restore are per database; point-in-time consistency across services does not exist.

**Likely follow-ups.**
- How do you report across services? Consume events into a read model or warehouse.
- Why the same Flyway SQL on H2 and PostgreSQL? Fast local tests with fidelity limits; Testcontainers closes the gap in CI.
- What if a service needs another's data synchronously? Prefer an event-fed copy; sync calls add coupling and failure paths.

### Q28. Scenario: inventory-service is down. What happens to order intake, in-flight orders and the other services?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Failure isolation and degradation

**Short answer.** Order intake continues to return 202 because validation is asynchronous. New orders stay `CREATED` while validation fails; in-flight orders wait in `VALIDATED` for reservation replies; Kafka and the outbox buffer commands. When inventory returns, retries and the recovery job drive everything forward without manual action unless attempts run out.

**Detailed answer.** Walk through each path using `docs/runbooks/01-orders-stuck-processing.md` and the order-service README failure table.

1. Intake: `POST /orders` writes order, saga and key rows and returns 202; it never calls inventory synchronously. Gateway routes to inventory (products, inventory endpoints) answer 503 `UPSTREAM_UNAVAILABLE` with `Retry-After: 5` once connect failures, timeouts or an open circuit occur; upstream 4xx and 5xx pass through unchanged (contract section 13).
2. Validation: `RestInventoryClient` retries 3 times then the breaker opens (50 percent of 10 calls). The saga keeps the order `CREATED`, counts attempts and records `lastError`; `SagaRecoveryJob` retries every 10 s for sagas idle over 60 s; after `max-attempts` (5) the order becomes `FAILED`.
3. Reservation: `ReserveInventory` is an outbox row then a Kafka record on `oms.inventory.commands.v1`; with inventory down it sits unconsumed, consumer lag grows, the order stays `VALIDATED`. When the consumer returns it processes the backlog from committed offsets; the recovery job may have re-sent the command, which is harmless because a duplicate for the same orderId returns the original outcome (unique `order_id`).
4. If attempts are exhausted in `VALIDATED`, `exhaust` fails the order and sends a defensive `ReleaseInventory` because the reply could have been lost. 
5. Other services: notification and fulfilment keep working on their own topics; reservation expiry (TTL 900 s) is an inventory job, so expiry also pauses.
6. Operators: `GET /api/v1/ops/health` reports inventory DOWN in the body (always 200); alerts `OmsServiceDown`, `OmsConsumerLagHigh`, `OmsSagasStuck`.

Customer experience: orders appear to hang; the UI polls and shows the true status. Communicate impact by order id only.

Honest limits: timing on 5 attempts spaced by the recovery interval is about a minute, so a 10-minute inventory outage will fail orders that were in `CREATED`; raise `oms.saga.max-attempts` and `stuck-after-seconds` to match the SLO, accepting slower failure detection. Multi-replica behaviour under a real outage has not been exercised.

**Trade-offs and failure modes.**
- Accepting orders during an outage gives customers a pending order that may later fail.
- Aggressive retries delay recovery by hammering a recovering service; the breaker's 10 s open window mitigates.
- Backlog drain after recovery can spike DB load (reservation writes).

**Likely follow-ups.**
- Should intake reject when inventory is down? A business choice; accepting trades certainty for availability.
- How do you drain safely? Watch lag and pool metrics; scale consumers up to partition count.
- Does `ops/health` need inventory? It reports each service, it does not depend on them for a 200.

### Q29. How do the gateway's edge responsibilities (authentication, rate limiting, header hygiene, correlation) fit together, and what is deliberately not trusted?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** API gateway responsibilities

**Short answer.** The gateway validates JWTs, rate limits per subject, strips untrusted headers, blocks internal paths, injects and echoes a correlation id, and wraps routes in circuit breakers. Backends re-validate everything, so the gateway is defence in depth, not the only gate.

**Detailed answer.** Order of filters (`EdgeFilters.Order`): correlation id first (accepts a well-formed inbound `X-Correlation-Id` or generates a UUID, forwards it and echoes it on every response), access log, `InboundHeaderSanitizer`, `PathGuardWebFilter` (answers `/api/v1/internal/**` with 404 before authentication and rejects traversal), then Spring Security's chain (order -100) with HS256 pinned and `iss`, `aud`, `exp`, `sub` validated, then `RateLimitWebFilter` (-60) and `RequestSizeLimitWebFilter` (-50, 64 KB).

Specific decisions: the rate limiter runs after authentication so a forged token cannot create buckets, keys are `sub:<subject>` for authenticated callers and `ip:<peer>` otherwise (behind a load balancer all anonymous callers would share the LB's address, as the class Javadoc notes), over-limit requests get 429 problem+json with `Retry-After`, and probes under `/actuator/` are exempt. SERVICE-role tokens are rejected at the edge (403) because services call each other directly. Edge role rules are defence in depth only; the services stay authoritative (ownership, 404 for others' objects). CORS is empty by default (same-origin UI via nginx or `ng serve` proxy); `*` is rejected for allowed origins. `POST /api/v1/auth/token` exists only in profile `dev`, protected by `LoginThrottle`.

What is not trusted: client-supplied identity headers (stripped), proxy headers (`Forwarded`, `X-Forwarded-*`), and the gateway itself (backends validate the same JWT). Upstream 4xx and 5xx are passed through unchanged; only connect failures, timeouts or an open circuit become 503 `UPSTREAM_UNAVAILABLE`.

No automatic gateway retries: `POST /orders` and similar are not safely repeatable at the edge (they rely on client-supplied idempotency keys).

Honest gaps: no WAF, mTLS or JWKS in the default configuration (the AWS reference mentions ALB, ACM and WAF, unapplied); the demo token endpoint and HS256 are demonstration-only.

**Trade-offs and failure modes.**
- Doing authorisation twice costs CPU but removes a single point of compromise.
- Rate limiting by subject protects fairness but not against token farming; add IP or WAF limits in production.
- Gateway timeouts (10 s) can exceed a client's own timeout; align them.

**Likely follow-ups.**
- Why 404 for `/api/v1/internal/**`? Hide existence and avoid auth probing.
- Why not trust gateway headers? A routing mistake would become privilege escalation (ADR-0010).
- Where do 429s show in dashboards? `http.server.requests` by status and the k6 `oms_rate_limited_429` counter.

### Q30. Scenario: You scale the gateway and order-service to many replicas. What happens to rate limiting, the outbox relay and scheduled jobs?
**Type:** Scenario   **Level:** Advanced   **Topic:** Multi-instance behaviour

**Short answer.** In-memory rate limits become per replica (effective limit multiplies), and the relay and scheduled jobs run on every replica. The design tolerates this (idempotent commands, `@Version`, de-duplicating consumers), but it wastes work and the READMEs explicitly say to add row claiming and job locks before running many replicas.

**Detailed answer.** Rate limiting: both the gateway (`InMemoryTokenBucketLimiter`) and order-service (`InMemoryOrderRateLimiter`, 20 burst and 5 per second per caller) default to in-memory buckets. With N replicas a caller can obtain up to N times the intended rate because requests are spread by the load balancer. With `oms.redis.enabled=true`, the gateway uses Spring Cloud Gateway's `RedisRateLimiter` shared across replicas, and any Redis error degrades to the in-memory limiter for that request (fail open to local limiting); order-service uses `RedisOrderRateLimiter` similarly. Redis behaviour against a real Redis is not verified (mocked template tests only).

Outbox relay: `OutboxRelay.poll` runs on every instance and `findPending` has no row claim, so two relays read the same oldest rows and both publish them. The comment states duplicates are tolerated by idempotent consumers. Costs: duplicate Kafka traffic, duplicate `processed_events` hits (`oms.events.duplicates` rises), and ordering across relay instances needs care (ADR-0009). The fix is `SELECT ... FOR UPDATE SKIP LOCKED` row claiming on PostgreSQL, or a single active relay, or CDC.

Scheduled jobs: `SagaRecoveryJob`, `ReconciliationService`, `IdempotencyKeyCleanupJob` and inventory's `ReservationExpiryJob` also run on each replica. They are safe because commands are idempotent, the saga row is `@Version`ed, and expiry uses status-guarded UPDATEs where exactly one wins; but they duplicate commands and add load. The README recommends ShedLock or enabling them on one instance; neither is implemented.

HPA settings in `infrastructure/kubernetes/base` allow 2 to 6 replicas per service; consumers beyond 3 per group are idle with 3 partitions. Verification status: single-JVM race tests exist (`ReservationConcurrencyTest`, optimistic retry tests); multi-instance behaviour of relay and jobs has not been run (README sections "Not verified").

```yaml
# README guidance, not implemented: before many replicas
# - relay row-claiming (FOR UPDATE SKIP LOCKED) or single active relay
# - recovery/reconciliation behind a lock (ShedLock) or on one instance
```

**Trade-offs and failure modes.**
- SKIP LOCKED improves throughput but can reorder events for a key unless claims are ordered by aggregate.
- A lock for jobs adds a dependency and a failure mode (stale lock).
- Fail-open limiter fallback means a Redis outage weakens fairness rather than blocking traffic.

**Likely follow-ups.**
- Why are duplicates acceptable? Consumers dedupe by `(consumer, event_id)`.
- Would you run the relay as a separate deployment? That is a clean option: one replica, no contention.
- How would CDC change this? Debezium reads the WAL; the outbox table shape stays the same.

### Q31. Scenario: A slow downstream service threatens to take down its callers. How do timeouts, retries and circuit breakers interact in this system, and what is missing?
**Type:** Scenario   **Level:** Advanced   **Topic:** Cascading failure and resilience patterns

**Short answer.** Timeouts bound waiting (connect 2 s, read 3 s outbound; gateway connect 2 s, response 10 s), retries mask transient errors but multiply load, and breakers stop sending traffic to a failing dependency. The repository applies these on the validation call and at the gateway but has no bulkheads and no retry budget.

**Detailed answer.** Layer by layer:
- Service to service: `OmsRestClientFactory` sets connect 2 s and read 3 s; `RestInventoryClient` adds Resilience4j retry (3 attempts, 200 ms doubling, only transient exceptions) inside a breaker (opens at 50 percent failures over 10 calls, minimum 5, open 10 s, 3 half-open probes). Worst case for one validation attempt chain is around 3 x 3 s plus backoff before the exception, which is why the call is made outside any database transaction and on a saga executor thread (queue 200, `AbortPolicy`).
- Edge: Netty connect timeout 2000 ms, `response-timeout` 10 s, time limiter 12 s as a backstop, per-route breakers (window 20, minimum 10 calls, 50 percent, 10 s open) with a 503 fallback. The gateway does not retry.
- Async paths: Kafka consumers use blocking retries (500 ms doubling, 4 attempts) then the DLT; this stalls one partition for a few seconds per failing record, bounded.

Amplification risk: retry multiplies at each layer. A client retrying a gateway request, the gateway not retrying, and the service retrying thrice makes a bad hop receive up to 3 attempts per saga pass, and the recovery job re-triggers every 10 s until `max-attempts`. That is bounded but not budgeted. The idempotent design makes the repeats safe, not free.

What is missing and how you would add it: a bulkhead (separate pool or semaphore) per downstream so a slow inventory cannot exhaust saga threads (the saga executor already isolates validation from request threads); a retry budget; jitter on the retry wait (Resilience4j supports randomized backoff; here exponential without jitter); per-route timeouts shorter than the default 10 s; load shedding at the gateway under saturation; and a half-open probe policy tuned from measurements. Also the breaker is per instance, so N replicas open independently.

Evidence: `InventoryClientResilienceTest` covers retry, timeout and breaker with a fake server; the platform runbook 06 (`docs/runbooks/06-service-unavailable.md`) covers the operator side. Not exercised: chaos or failure injection against a real cluster.

**Trade-offs and failure modes.**
- Short timeouts cause false failures during GC pauses; long ones pile up threads.
- A breaker that opens too eagerly sheds healthy traffic; too lazily it protects nothing.
- Fallback responses must be honest (503 with `Retry-After`), not fake data.

**Likely follow-ups.**
- Retry inside or outside the breaker? Here retry is outside, so the breaker sees every attempt.
- Why no gateway retries? Non-idempotent POSTs.
- Bulkhead types? Semaphore versus thread-pool; the thread-pool variant adds overhead but isolates blocking calls.

### Q32. Scenario: Traffic doubles. Which components scale horizontally, and what stops them from scaling further?
**Type:** Scenario   **Level:** Advanced   **Topic:** Kubernetes scaling, HPA, KEDA, partitions

**Short answer.** Stateless tiers (gateway, services) scale with replicas, but consumer parallelism is capped by 3 partitions per topic, PostgreSQL connections and hot rows cap the data tier, and the relay and scheduled jobs add duplicate work per replica. Autoscaling manifests exist but have only been statically validated.

**Detailed answer.** Current manifests (`infrastructure/kubernetes/base`): every Deployment has 2 replicas, `maxUnavailable: 0`, a PodDisruptionBudget (`minAvailable: 1`), topology spread constraints, resource requests and limits (order-service 250m CPU and 512Mi request; 1 CPU and 1Gi limit), and a `HorizontalPodAutoscaler` on 70 percent CPU with 2 to 6 replicas. CPU scaling suits request-bound work (gateway, REST reads); it reacts poorly to Kafka backlogs where CPU may be low while handlers wait on the database.

`infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` is an example, not part of any overlay: inventory-service scales on lag of `oms.inventory.commands.v1` (`lagThreshold 50`, activation 5, plus CPU 70) with min 2 and max 3, and notification-service on `oms.order.events.v1` (threshold 100). The header warns not to keep the base HPA on the same Deployment (KEDA creates its own) and that `maxReplicaCount` must not exceed the 3 partitions or the extra consumers idle. Note the base HPAs allow 6 replicas, so for consumer-heavy services 4 to 6 replicas add REST capacity but zero consumer capacity.

Limits as you scale:
- Partitions: group parallelism is at most the partition count (3). Changing it later changes key-to-partition mapping and breaks ordering during migration (ADR-0002); plan up front. `oms.messaging.concurrency` (default 1) lets one pod use several threads, still within partitions.
- Database: `replicas x pool` versus `max_connections` (order pool 10, inventory 20). More replicas increase contention on hot `stock` rows.
- Outbox relay and jobs: duplicated per replica (Q30).
- Redis for shared rate limits, otherwise limits multiply.
- JVM start: startup probe allows 3 minutes (JVM plus Flyway); scale-out is not instant. Layered images make pulls cheaper.
- Cost: 6 replicas x 4 services x 1 Gi.

Verification: manifests are checked with kustomize build and kubeconform in CI (`infra-validate`); no cluster run. The prod overlay uses placeholder endpoints.

**Trade-offs and failure modes.**
- Scaling on lag alone ignores handler cost; combine with CPU or DB signals.
- Scale-in during processing triggers rebalances; graceful shutdown limits damage.
- Over-provisioned partitions increase file handles and replication traffic.

**Likely follow-ups.**
- Why not scale consumers beyond partitions? They sit idle.
- Why a PDB? Voluntary disruptions cannot take all replicas at once.
- How would you scale inventory writes beyond one hot row? Bucketed stock or queued reservations per SKU.

## Kafka and event-driven

### Q33. How is the Kafka topology designed (topics, keys, partitions, envelope), and what do those choices buy and cost?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Kafka topic and event design

**Short answer.** One topic per interaction (events and commands separated), several event types per topic distinguished by `eventType`, all keyed by `orderId` with 3 partitions, a common JSON `EventEnvelope`, and JSON Schema files instead of a schema registry. Per-order ordering holds; cross-order and cross-topic ordering do not.

**Detailed answer.** Topics (`Topics` constants in `services/platform-common/.../common/events`): `oms.order.events.v1` (produced by order-service), `oms.inventory.commands.v1` (order to inventory), `oms.inventory.events.v1`, `oms.fulfillment.commands.v1`, `oms.fulfillment.events.v1`, plus `<topic>.DLT`. Each topic has one producing service, so each schema has one authority. Commands (`ReserveInventory`, `ReleaseInventory`, `StartFulfillment`) are separate from events so authorisation, retention and consumers can differ. Consumer group id equals the service name. Contract section 7 states 3 partitions and replication factor 1 locally and 3 in production; the Compose broker is a single-node KRaft `apache/kafka:3.9.1` with auto topic creation disabled (topics come from `infrastructure/compose/kafka/create-topics.sh`), and the local stack uses embedded KRaft on port 19092 from `services/local-stack`. Replication 1 is demonstration-only.

The key is `orderId`, so every event for one order lands in one partition and is consumed in order (an `InventoryReleased` cannot overtake its `InventoryReserved`). The envelope carries `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `causationId`, `aggregateType`, `aggregateId`, `producer`, `payload`; the Kafka headers `correlationId`, `eventType`, `schemaVersion`, `eventId` (and `traceparent`) duplicate what routing and tracing need without parsing the body.

Rejected alternatives (ADR-0002): one topic per event type (15 or more topics, and reserved/released could arrive out of order on different topics), a single global topic (no ACL separation, noisy lag), Avro or Protobuf with a registry (stronger compatibility enforcement but infrastructure that cannot run in the Docker-less local stack; JSON Schema in `specs/events` plus contract tests is the compromise).

Costs: multiple types per topic mean each consumer discards unhandled types (`EventRouter` counts them in `oms.events.ignored`); a hot order key cannot be spread; changing the partition count later reshuffles keys; there is no ordering between topics, which is why notification keeps `order_contacts` and may retry a fulfilment event whose contact is unknown yet. JSON is bigger and unchecked at publish time unless the contract tests run.

**Trade-offs and failure modes.**
- Replication 1 means a broker loss loses data; production needs RF 3 and `min.insync.replicas` 2 (the AWS reference states MSK with those values, unapplied).
- Auto topic creation off prevents accidental topics but requires the init job.
- Skewed keys (one giant order) create a hot partition.

**Likely follow-ups.**
- Why key by orderId rather than customerId? Ordering is needed per order; customer keys would serialise unrelated orders.
- Can two orders interleave? Yes, in different partitions or the same; no cross-order guarantee is required.
- Why JSON Schema without a registry? Runs on a laptop; revisit with more producers.

### Q34. Scenario: Handle duplicate Kafka events
**Type:** Scenario   **Level:** Advanced   **Topic:** Idempotent consumers and delivery semantics

**Short answer.** Assume duplicates are normal (at-least-once). Each consumer inserts `processed_events(consumer, event_id)` in the same database transaction as its business effect, so a redelivery violates the primary key or finds the marker and is skipped; reservations and the state machine add idempotency for the same logical command arriving under a new `eventId`.

**Detailed answer.** Where duplicates come from here: the outbox relay re-publishing a row when it crashed after the broker ack but before marking `published_at`; consumer redelivery after a rebalance or a crash between commit and offset commit; the saga recovery job deliberately re-sending commands with new event ids; and client retries of `POST /orders`. Kafka itself provides exactly-once only inside Kafka; this platform claims effectively-once business effect, not exactly-once end to end (`docs/architecture/delivery-semantics.md`).

Layer 1, consumer marker. `IdempotentEventProcessor.processOnce`:

```java
if (repository.existsById(key)) { metrics.duplicate(topic, envelope.eventType()); return false; }
repository.saveAndFlush(new ProcessedEvent(consumer, envelope.eventId(), envelope.eventType(), topic, Instant.now()));
effect.run();
```

It is `@Transactional`; the marker and the effect commit or roll back together. Race: two concurrent deliveries both pass `existsById`; the loser fails on the primary key `(consumer, event_id)` and rolls back, so its effect is not applied; the container retries it and then sees the duplicate. Layer 2, domain idempotency: inventory reservations are unique per `order_id` (`ReservationClaims`), so a repeated `ReserveInventory` returns the original outcome and re-emits the matching event; `ReleaseInventory` and ship are guarded no-ops; the order state machine treats an already-applied transition as a no-op and stale events are audited as `TRANSITION_IGNORED`. Layer 3, notification adds unique `(source_event_id, template)`.

Observability: `oms.events.duplicates` (benign when low and tied to a restart or rebalance window; PromQL ratio in runbook 05), `/api/v1/ops/messaging` field `duplicatesIgnored`, and SQL to prove it:

```sql
select consumer, event_id, event_type, topic, processed_at from processed_events where event_id = :id;
select source_event_id, template, count(*) from notifications group by 1,2 having count(*) > 1;
```

Diagnosis rule from runbook 05: the same `eventId` twice with one effect is a benign redelivery; different eventIds for one logical change means a producer bug; the same eventId with two effects means the marker was not in the same transaction (self-invocation, Q10) or the consumer name differs between code paths.

Limits: the marker table must be retained as long as the maximum redelivery horizon (topic retention plus DLT replay window); I found no purge job for `processed_events` in the code, so it grows. A real external side effect (an email provider) would need the provider's own idempotency key; notifications here are simulated.

**Trade-offs and failure modes.**
- One extra insert per consumed event.
- A purge shorter than retention reopens the duplicate window.
- Dedup by `eventId` does not catch a producer that builds two events for one change.

**Likely follow-ups.**
- Why not Kafka transactions? They do not cover the PostgreSQL commit or HTTP clients (ADR-0005).
- Why include `consumer` in the key? Several services consume the same event; each needs its own marker.
- What if the effect is idempotent already? You still keep the marker for metrics and cheap short-circuit.

### Q35. Scenario: Recover a failed Kafka consumer
**Type:** Scenario   **Level:** Advanced   **Topic:** Consumer failure and recovery

**Short answer.** First establish why it is not consuming (no members, one stuck partition, slow handler, rebalances, DB or broker trouble), fix the cause, let the consumer resume from committed offsets, and rely on idempotent handlers for safe redelivery. Avoid resetting offsets: that skips saga steps.

**Detailed answer.** Use runbook 02 (`docs/runbooks/02-kafka-consumer-lag.md`). Symptoms: `consumerLag` growing in `GET /api/v1/ops/messaging` (`lagAvailable:false` means the AdminClient could not reach the broker, itself a finding), `oms.events.processed` rate near zero, `oms.outbox.pending` rising if the problem is the broker rather than the consumer. Diagnose with `kafka-consumer-groups.sh --describe --group inventory-service` (group id equals service name; look at `CURRENT-OFFSET`, `LOG-END-OFFSET`, `LAG`, and an empty `CONSUMER-ID`).

Decision tree: all partitions lagging with no consumer id means no active members (pods crash-looping or not ready, or a listener container stopped after a fatal error: restart and fix the cause). Lag on one partition only means a hot key or a poison record at the head; check the `oms.events.retries` metric and logs for the same offset repeating. Consumers alive but slow means a slow handler (DB latency, row locks), repeated rebalances (processing exceeding `max.poll.interval.ms`, Q4), or retry backoff stalling a partition. Producer side: `oms.outbox.pending` growing with idle consumers means the relay or broker is at fault.

What recovery looks like mechanically: committed offsets are per group, `AckMode.RECORD` commits after each successfully processed record or after the recoverer stores it in the DLT, and `auto.offset.reset=earliest` applies only when there is no committed offset. When the service comes back it resumes at the first uncommitted record. Records processed but not yet committed are redelivered and skipped by `processed_events`. If the failing record is poison, the error handler retries transient errors (500 ms, 1 s, 2 s) and dead-letters it; non-retryable types go straight to `<topic>.DLT`, so the partition moves on (Q37).

Actions in order: restore capacity (scale up to the partition count, 3, via `kubectl scale`), fix the slow dependency rather than raise poll intervals, inspect the DLT, replay only after the fix, and run `POST /api/v1/admin/reconciliation/run` afterwards (ADMIN). Never run `--reset-offsets` on a production group without approval; stop the consumers first and use `--dry-run`. Alerts: `OmsConsumerLagHigh` (lag above 1000 for 5 minutes), `OmsDeadLetterRecords`, `OmsOutboxBacklog`, `OmsRetryStorm`.

Honest status: runbook commands are illustrative and have not been run against a live cluster; consumer retry, DLT and non-retryable behaviour are tested on embedded Kafka (`ConsumerKafkaTest`, `OutboxRelayKafkaTest`).

**Trade-offs and failure modes.**
- Scaling beyond 3 consumers per group does nothing.
- Replaying old events late requires handlers tolerant of stale transitions.
- Resetting offsets to latest loses events; to earliest replays everything (duplicates skipped, but cost and ordering effects).

**Likely follow-ups.**
- Does a failed consumer lose messages? No, Kafka retains them until retention; the risk is exceeding retention.
- What if offsets expire? Behaviour follows `auto.offset.reset`; here `earliest`.
- How do you test it? Stop the consumer, produce 20 orders, observe lag, restart (runbook 02 exercise).

### Q36. How does the transactional outbox work in this platform, and what are its failure windows?
**Type:** Conceptual   **Level:** Advanced   **Topic:** Transactional outbox

**Short answer.** State changes and their events are written in the same database transaction (`OutboxService.append`, which requires an existing transaction). A polling `OutboxRelay` publishes pending rows with `acks=all` and an idempotent producer, then marks `published_at`, giving at-least-once publication.

**Detailed answer.** `outbox_events` lives in every producing service (`V1__outbox_processed_audit.sql` in `platform-common`). `OutboxService.append` has `@Transactional(propagation = Propagation.MANDATORY)`, so calling it outside a transaction throws instead of silently publishing nothing; for example the orchestrator writes `OrderCreated` and `ReserveInventory` in the same transaction as the CREATED to VALIDATED transition, history, saga step log and audit rows.

`OutboxRelay.poll` runs every 500 ms (`oms.outbox.poll-interval-ms`), loads up to 50 oldest pending rows (`findPending` orders by `created_at, id`), and for each calls `kafka.send(record).get(sendTimeoutMs)` (5 s) with the stored headers, then marks the row in a separate transaction. A failure stops the batch (preserving order and not hammering a broken broker), records `attempts` and `last_error`, and never throws out of the scheduled method. Published rows are deleted after one hour by `cleanup`. The producer bean `omsKafkaTemplate` sets `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `retries` max and bounded `max.block.ms`, request and delivery timeouts.

```java
kafka.send(record).get(props.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
...
markPublished(event);   // separate transaction; if it fails the row is re-sent (at-least-once)
```

Failure windows (`delivery-semantics.md`): crash before commit means nothing happened; crash after commit and before publish leaves a row that publishes after restart (`oms.outbox.pending` rises, alert `OmsOutboxBacklog` above 100 for 5 minutes); broker down means rows accumulate and the relay retries; broker acked but relay died before marking means a duplicate on the topic, which consumers must absorb (Q34); producer retry inside a session is deduplicated by idempotence.

Why not alternatives (ADR-0009): dual write loses or invents events; Kafka plus DB transaction coordination has no standard atomic commit; CDC (Debezium) is a valid production upgrade but needs Kafka Connect and logical replication that cannot run in the Docker-less local stack. Scaling caveats: no row claiming, so multiple relay replicas duplicate publishes; `FOR UPDATE SKIP LOCKED` or a single active relay is the stated next step. Verified by `OutboxRelayKafkaTest` and `OutboxRelayResilienceTest` and the Kafka-down integration test in order-service; not verified against a real broker failure.

**Trade-offs and failure modes.**
- Added latency (poll interval) and table growth.
- Polling load on the database.
- Ordering across multiple relay instances needs care.

**Likely follow-ups.**
- Why delete published rows after an hour? Table size; the cost is that you cannot audit old publications from it (audit rows exist).
- Why `MANDATORY`? To catch missing transactions at the first call.
- What if serialization fails when appending? The exception rolls back the business transaction, which is the right default.

### Q37. Explain the consumer error-handling pipeline: retries, non-retryable errors and the dead-letter topic.
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Spring Kafka error handling

**Short answer.** Listeners are plain `ConsumerRecord<String,String>` consumers with `AckMode.RECORD`. A `DefaultErrorHandler` retries transient failures with exponential backoff (4 attempts: 500 ms, 1 s, 2 s waits), classifies some exceptions as non-retryable (straight to the DLT), and a `DeadLetterPublishingRecoverer` publishes exhausted records to `<topic>.DLT` before the offset advances.

**Detailed answer.** `KafkaConsumerConfigSupport.containerFactory(groupId)` uses `StringDeserializer` for key and value on purpose: the envelope is parsed by `EventRouter`, so malformed JSON becomes a `MalformedEventException` in application code (non-retryable) rather than a deserialisation failure inside the consumer that could block a partition. Auto commit is off. Listeners in each service are one-liners, for example `InventoryEventsListener` declares three `@KafkaListener` methods (`inventory-commands`, `inventory-order-events`, `inventory-fulfillment-events`) that call `router.route(record)`; the router parses, sets the MDC correlation id, ignores unknown event types (counted) and runs the handler inside `processOnce`.

Classification:

```java
public static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
    NonRetryableEventException.class, IllegalArgumentException.class,
    jakarta.validation.ConstraintViolationException.class, DeserializationException.class,
    MessageConversionException.class, ClassCastException.class);
```

`handler.addNotRetryableExceptions(...)` registers them. Everything else is retried: `ExponentialBackOffWithMaxRetries(maxAttempts - 1)` with initial 500 ms, multiplier 2.0, max 5 s (properties `oms.messaging.retry.*`). The DLT recoverer sends to `new TopicPartition(rec.topic() + ".DLT", -1)` (partitioner chooses the partition, key kept) with headers `x-exception-class`, `x-exception-message`, `x-original-topic`, `x-original-partition`, `x-original-offset` besides Spring's `kafka_dlt-*` headers. A `RetryListener` increments `oms.events.retries` per failed non-final delivery, and the recoverer increments `oms.events.dead_lettered` and logs topic, partition, offset and root cause (no payload, to avoid personal data).

Behavioural consequences: while one record retries the partition is blocked (blocking retries), bounded to a few seconds; a handler exception rolls back the transaction including the `processed_events` marker, so a retry is a clean first attempt; swallowing exceptions or acknowledging in a `finally` block bypasses all of this and silently loses events (code review 05). Non-blocking retry topics (`@RetryableTopic`) are not used. Tested by `ConsumerKafkaTest` on embedded KRaft Kafka (retry, DLT, non-retryable).

**Trade-offs and failure modes.**
- Classifying a transient error as non-retryable dead-letters recoverable events; the reverse wastes retries on poison.
- Blocking retries reduce throughput during incidents.
- If DLT publishing fails, the record is not skipped; it is attempted again (check logs for recovery failures).

**Likely follow-ups.**
- Why `String` deserialisers? Parse failures stay in our code and classify cleanly.
- What counts as a business error? Throw `NonRetryableEventException` without personal data in the message.
- Where do ignored events go? Nowhere; they are counted in `oms.events.ignored`.

### Q38. Scenario: Records are appearing in a dead-letter topic. How do you inspect them and decide whether to replay?
**Type:** Scenario   **Level:** Intermediate   **Topic:** DLT triage and replay

**Short answer.** Read the DLT headers to see the exception class and original coordinates, classify poison versus transient, fix the cause, then replay only valid records back to the original topic with their original key. The repository documents this procedure but ships no replay tool.

**Detailed answer.** Trigger: alert `OmsDeadLetterRecords` (any increase in 15 minutes, critical) or `deadLettered` in `GET /api/v1/ops/messaging`, which also lists `dltTopics`. Inspect (from `docs/mentoring/debugging.md` and runbook 02):

```bash
kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic oms.inventory.events.v1.DLT \
  --from-beginning --max-messages 5 --property print.key=true --property print.headers=true
```

Decision table keyed on `x-exception-class`: `MalformedEventException` or deserialisation errors mean the producer sent bad JSON or schema drift (compare with `specs/events/*.schema.json`; not replayable as-is); `NonRetryableEventException` or `IllegalArgumentException` mean a field failed validation (poison, find the bug); `InvalidStateTransitionException` means unexpected ordering (the state machine should have made duplicates a no-op, so look at redelivery handling); `DataAccessException` and timeouts mean transient failures whose retries were exhausted (fix the dependency, then replay).

Replay mechanics: the DLT record keeps the original key (orderId) and value; `x-original-topic` tells you where it belongs. Re-publish the value with the same key to the original topic (a small producer script or console producer with `parse.key`). Replay is safe only because consumers are idempotent, and because handlers tolerate late events: the state machine rejects or ignores stale transitions (audited as `TRANSITION_IGNORED`). Replay in order per key, after the fix is deployed, a handful first, and watch `oms.events.dead_lettered` and `duplicates`. Check retention: `processed_events` must still cover the replay window, otherwise an event applied earlier could apply again where the effect is not otherwise idempotent.

Business follow-up: a dead-lettered `ReserveInventory` leaves an order in `VALIDATED`; the recovery job re-sends the command (a new record, not the DLT one), so a stuck order often heals without replay, and `POST /api/v1/admin/orders/{id}/retry` or reconciliation covers the rest. Do not edit `orders.status` by SQL: it skips history, audit and outbox.

Honest status: there is no automated replayer and no UI for DLTs (ADMIN is meant to inspect them per the contract role table); runbook commands are illustrative. Poison-message behaviour (straight to the DLT without retries) is tested on embedded Kafka.

**Trade-offs and failure modes.**
- Replaying a record that fails again dead-letters it again (a loop if automated blindly).
- DLT retention shorter than triage time loses evidence.
- DLT payloads contain personal data (customer email); restrict access.

**Likely follow-ups.**
- Why keep the key on the DLT record? So replay preserves per-order ordering.
- Should you auto-replay? Not without classification; transient and poison need different handling.
- What proves nothing was lost? Compare order saga state and reconciliation output.

### Q39. Scenario: Consumer lag grows after a traffic spike. How do you find the bottleneck and scale safely?
**Type:** Scenario   **Level:** Advanced   **Topic:** Consumer throughput and partitions

**Short answer.** Establish whether lag is a capacity problem (handlers slow or too few consumers), a skew problem (one hot partition) or a fault (poison, rebalances). Capacity is capped by 3 partitions per topic and by database throughput; scale consumers only up to the partition count and fix the DB path first.

**Detailed answer.** Measure: `kafka_consumer_fetch_manager_records_lag_max` per topic (the `OmsConsumerLagHigh` alert fires above 1000 for 5 minutes), `rate(oms_events_processed_total[5m])` by topic, `oms_events_retries_total`, `oms_outbox_pending` (producer-side delay), plus `hikaricp_connections_pending` and `http_server_requests` p99. If processed rate is below the arrival rate, compute the gap: with handler time `t` per record and one consumer thread per partition, per-partition throughput is about `1/t`. For reservations the handler writes slot, items, stock, movement, outbox and audit rows in one transaction; the benchmark in the inventory README shows roughly 60 to 830 whole-reservation operations per second depending on strategy and contention (laptop, one PostgreSQL), so the DB, not Kafka, is usually the limit.

Levers and their limits:
- More consumers: group parallelism is at most 3 (partitions). `oms.messaging.concurrency` defaults to 1 thread per instance (`OMS_KAFKA_CONCURRENCY`), so three single-thread pods or one pod with concurrency 3 reach the cap. Beyond that, partitions must increase; that reshuffles the key mapping, so migrate deliberately (ADR-0002).
- Autoscaling: base HPAs scale on CPU (2 to 6), which poorly reflects lag; `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` is an example scaler on lag (not in any overlay), max 3 replicas to match partitions.
- Skew: one hot order or a storm for one key stays on one partition; look at per-partition lag with `kafka-consumer-groups.sh --describe`.
- Handler cost: batch less, keep transactions short, add indexes (`docs/database/inventory-explain-analyze.md`), avoid remote calls inside handlers.
- Retry stalls: a failing record blocks its partition for about 3.5 s of backoff per record; a dependency outage turns every record into a retrier. Fix the dependency instead of waiting.
- Rebalances: processing longer than `max.poll.interval.ms` (default 5 minutes here) triggers eviction (Q4).

Safe scale procedure: scale consumers gradually while watching DB pool and lock metrics; after the spike, lag drains at arrival-rate minus processing-rate; with the outbox and Kafka retention the backlog is durable. Never reset offsets to skip a backlog: that skips saga steps. Reconcile afterwards.

Honest status: no load numbers exist for this repository; the k6 scripts (`performance/order-flow.js`) have not been run and thresholds are initial targets. The inventory benchmark (16 threads, PostgreSQL 16.4 from a Maven-cached binary on a laptop) is an indicator only.

**Trade-offs and failure modes.**
- More consumers increase DB contention and may reduce per-reservation throughput on hot rows.
- Adding partitions without a plan breaks ordering for in-flight orders.
- Lag alone is a lagging indicator; alert on its growth rate too.

**Likely follow-ups.**
- Why not raise `max.poll.records`? Records are processed one transaction each; it only lengthens the poll gap.
- Is lag on `oms.order.events.v1` urgent? Mostly notification delay; inventory consumers only act on confirm and cancel.
- How do you size partitions? Target consumers times headroom, planned before launch.

### Q40. Scenario: You must add a field to an event and later rename another. How do you evolve the schema without breaking consumers?
**Type:** Scenario   **Level:** Advanced   **Topic:** Event schema evolution

**Short answer.** Adding an optional field is non-breaking: producers write it, tolerant-reader consumers ignore or use it, and `schemaVersion` is bumped. A rename or type change is breaking: add the new field alongside the old, migrate consumers, and remove the old field only in a new topic version with dual publishing.

**Detailed answer.** Rules from ADR-0002 and `docs/architecture/event-schema-evolution.md`: `schemaVersion` in the envelope (and header) is per event type and increments for additive change; the topic suffix (`.v1`) changes only for breaking change; consumers use a tolerant reader (`FAIL_ON_UNKNOWN_PROPERTIES=false`); an unknown `eventType` is ignored and counted; a malformed record goes to the DLT without retries. JSON Schemas in `specs/events/*.schema.json` use `additionalProperties: true`, generated by `scripts/gen-event-schemas.mjs` and checked by `scripts/validate-events.mjs`.

Worked example in the guide (a design example, not implemented in the code): add optional `shippingMethod` to `OrderCreated`. Steps: add the optional API field, persist it with a nullable column via a new Flyway migration (V10 or higher), add it to the payload and set `schemaVersion` 2, update the schema (not in `required`), deploy notification reading it with an upcast default (`STANDARD`), in any order, because old consumers ignore it and new consumers treat absence as the default. Inventory needs no change since it ignores `OrderCreated`. Tests: contract fixtures for v1 and v2 per consumer and a producer test validating against the schema.

Breaking example: replacing `shippingCity` with a structured `shippingAddress`. Add `shippingAddress` alongside `shippingCity` for at least one release; migrate consumers; remove `shippingCity` only on `oms.order.events.v2` with dual publishing (two outbox rows in one transaction, so both topics get the event atomically with respect to the producer), switch consumers one group at a time, stop v1 when lag on v1 is zero and a retention period has passed, delete v1 last. Replay safety: DLT and retained records keep their original `schemaVersion`, so handlers must read every version still in retention (upcasting at the edge of the `EventRouter` handler).

Other breaking changes: making an optional field required, changing a field's meaning, units or type, changing the key (partitioning changes ordering). Honest gaps: there is no schema registry or automated compatibility check on publish; safety rests on JSON Schema validation scripts and contract tests, and the guide itself says to revisit Avro or Protobuf when producers multiply.

**Trade-offs and failure modes.**
- Dual publishing doubles traffic and consumer complexity during migration.
- A tolerant reader can hide a producer bug that drops a field; contract tests catch it.
- Missing optional fields become `null`; consumers must default them.

**Likely follow-ups.**
- Deploy order for additive changes? Any order; consumers first is safest.
- Why not bump the topic for every change? It forces coordinated migrations for harmless additions.
- How do you test a version bump? Fixtures of old and new events fed to each consumer.

## Saga and eventual consistency

### Q41. Scenario: Design a saga across order and inventory services
**Type:** Scenario   **Level:** Advanced   **Topic:** Saga design

**Short answer.** Use an orchestrated saga owned by order-service: persist saga state, send idempotent commands through the transactional outbox, react to idempotent events, define a compensation for every step that holds a resource, and add timeouts, a recovery job and reconciliation because messages and replies can be lost. There is no distributed ACID transaction; compensation is semantic.

**Detailed answer.** Requirements: an order must reserve stock held by another service, never oversell, and clean up on failure or cancellation, while each service keeps its own database (ADR-0004).

Design as implemented (`OrderSagaOrchestrator`, `OrderStateMachine`, `SagaRecoveryJob`, `ReconciliationService` in `services/order-service`):

1. Accept: one local transaction writes the order (`CREATED`), items, `saga_instances` row, history and audit and the idempotency key; reply 202.
2. VALIDATE: synchronous REST to inventory (outside any transaction, breaker plus retry). On success, one transaction moves the order to `VALIDATED`, writes `saga_step_log` and appends two outbox rows: `OrderCreated` and the command `ReserveInventory` (topic `oms.inventory.commands.v1`, key `orderId`).
3. RESERVE_INVENTORY (inventory-service): inside one transaction, claim the reservation slot (unique `order_id`), reserve all lines atomically, write items, movement and outbox row, then emit `InventoryReserved` or `InventoryReservationFailed` (with shortages). All-or-nothing.
4. CONFIRM_ORDER: on `InventoryReserved` the orchestrator, in one transaction, moves `INVENTORY_RESERVED` to `CONFIRMED` and appends `OrderConfirmed` and `StartFulfillment`; inventory commits the reservation on `OrderConfirmed`.
5. FULFILL: fulfilment events complete the order (`FULFILLED`) and inventory ships stock on `FulfillmentCompleted`.

Compensations: insufficient stock means `REJECTED` (nothing to undo, the reservation rolled back); cancel from `CREATED` or `VALIDATED` means `CANCELLED` immediately; cancel or fulfilment failure after a reservation means `COMPENSATION_PENDING`, command `ReleaseInventory{reason}`, then `InventoryReleased` closes the order as `CANCELLED` or `FAILED`; TTL expiry (900 s) releases by itself and fails the order.

Failure handling: every command is idempotent at the receiver (duplicate `ReserveInventory` returns the original outcome; releasing an unknown reservation writes a `RELEASED` tombstone so a late reserve cannot take stock); consumers dedupe by `processed_events`; the saga row is `@Version`ed and handlers re-read and re-decide on conflict (`RetryingTransactions`, up to 5 attempts); `SagaRecoveryJob` re-sends the current step's command after `oms.saga.stuck-after-seconds` (60) up to `oms.saga.max-attempts` (5), then fails non-compensable steps or flags compensation as `NEEDS_ATTENTION`; `ReconciliationService` repairs known inconsistencies; operators have `GET /api/v1/admin/saga/{orderId}` and `POST /api/v1/admin/orders/{id}/retry`.

```java
// OrderSagaOrchestrator.applyValidation (inside one transaction)
saga.advanceTo(SagaStep.RESERVE_INVENTORY, Instant.now());
messaging.orderCreated(order, null);
messaging.reserveInventory(order, null);
```

Design questions interviewers probe: why the orchestrator is not a single point of failure for correctness (state persisted, recovery job, stateless instances) but is a coordination hot spot (ADR-0003); why commands and replies are asynchronous (decoupling, backlog tolerance) at the price of duplicates and latency; why notification is outside the compensable path (a sent email cannot be unsent).

Verification: state machine tests cover every status pair (207 cases), Kafka integration tests on embedded KRaft cover happy path, insufficient stock, cancellation, fulfilment failure, duplicates, out-of-order and poison messages. Not run: multi-instance and real-PostgreSQL saga flows.

**Trade-offs and failure modes.**
- Intermediate states are visible to users and other services; no isolation across steps.
- A workflow engine (Temporal, Camunda, Step Functions) would give timers and retries for free but adds a runtime (ADR-0003).
- Not every action is reversible; design steps so the last irreversible one comes last.

**Likely follow-ups.**
- Why not two-phase commit? No standard atomic commit across PostgreSQL and Kafka; availability and coupling costs.
- Where is the saga state? `saga_instances` and `saga_step_log` in `order_db`.
- How do you test compensations? Kafka integration tests with simulated replies, plus exhaustive state-machine pairs.

### Q42. Scenario: Prevent overselling inventory
**Type:** Scenario   **Level:** Advanced   **Topic:** Concurrency control for stock reservation

**Short answer.** Never check stock in Java and then write it. Make the check and the update one atomic SQL statement (`UPDATE stock SET reserved = reserved + :q ... WHERE on_hand - reserved >= :q`) and treat zero affected rows as insufficient stock, inside an all-or-nothing transaction with deterministic lock order. This repo implements that as the default `AtomicUpdateReservationStrategy` and keeps pessimistic and optimistic variants for comparison.

**Detailed answer.** The bug (`docs/code-review/01-oversell-race.md`): read `on_hand - reserved`, compare in Java, then save `reserved + q`. Two transactions at READ COMMITTED both read available 3, both write `reserved = 2`; two reservations of 2 are recorded against stock 3. No exception, no metric. Sequential tests cannot find it; a concurrent test can.

Options, as implemented in `services/inventory-service/.../reservation`:
1. Atomic conditional UPDATE (`AtomicUpdateReservationStrategy`, default, `oms.inventory.reservation-strategy=atomic`): the row lock taken by the UPDATE serialises writers and the predicate is re-evaluated against the latest committed row after waiting. `StockRepository.tryReserve` returns 1 or 0. On a shortage on line k, lines already taken are given back with `release` (we still hold their row locks).
2. Pessimistic (`PessimisticLockReservationStrategy`): `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`) every line in order, then check and write; nothing to compensate and a full shortage list, but rows are locked even for a "no" and hot rows queue for the whole transaction.
3. Optimistic (`OptimisticVersionReservationStrategy`): read `(on_hand, reserved, version)`, then compare-and-set `WHERE version = :seen`, bounded retries (`optimistic-max-retries` 20) with randomised backoff, then `ReservationContentionException`. The CAS is SQL, not a failing entity flush, because a failed Hibernate flush poisons the transaction that also carries the processed-event marker.

```sql
UPDATE stock SET reserved = reserved + :q, version = version + 1
 WHERE product_id = :id AND on_hand - reserved >= :q
```

Reservation model around it: lines are merged, validated and sorted by `productId` (global lock order, no deadlocks between orders with opposite line order); the order's slot is claimed first (unique `order_id`, plain INSERT in a savepoint) so duplicates return the original outcome; `RESERVED` counts in `stock.reserved` with a TTL (900 s), becomes `COMMITTED` on `OrderConfirmed`, and shipping does `on_hand -= q; reserved -= q`; every transition is a status-guarded UPDATE; CHECK constraints `reserved <= on_hand`, `on_hand >= 0`, `reserved >= 0` are the last line of defence; admin stock edits use JPA `@Version` with bounded retry and are rejected below `reserved` (409).

Evidence: `ReservationConcurrencyTest` runs 50 threads of 1 unit against stock 10 for each strategy and expects exactly 10 reserved, plus opposite item orders and duplicate commands; `PostgresScenarios` ran on a real PostgreSQL 16.4 (Maven-cached binary, not Docker); k6 `performance/oversell.js` races 40 customers for SKU-1019 (stock 3) and is a correctness gate (never executed here). Benchmark (laptop, README): hot row atomic 129 ops/s, pessimistic 164, optimistic 64 with 2,389 conflicts; spread over 200 products about 800 for all three. Takeaway: one hot row serialises whatever you do; atomic is the safe default.

**Trade-offs and failure modes.**
- Logic in SQL is less portable and less visible.
- The hot path bumps `version`, so an admin edit racing reservations can see optimistic conflicts (retried).
- A hot SKU needs bucketing or queuing; not implemented.

**Likely follow-ups.**
- Why not `SERIALIZABLE`? It would force retries on serialization failures across unrelated rows.
- Is cache stock ever used to decide? Never; reservations read the database.
- What if stock is adjusted down? Rejected below `reserved`.

### Q43. Why orchestration rather than choreography for the order saga, and where does this system still use choreography?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Saga styles

**Short answer.** Orchestration gives one place and one table that answers "where is order X and why", an explicit compensation path and a single owner for recovery. Choreography remains where no coordination is needed: notification just consumes events, and inventory consumes `OrderConfirmed` and `OrderCancelled` as a safety net.

**Detailed answer.** ADR-0003 weighed choreography (each service reacts and emits, no owner), orchestration (chosen) and a workflow engine. Drivers: operators need a one-query answer for a stuck order (`GET /api/v1/admin/saga/{orderId}` shows state, step, attempts, last error and the step log); compensation paths (cancel, fulfilment failure, reservation expiry) must be explicit and exhaustively tested; recovery of lost messages needs an owner; participants should stay decoupled (inventory and fulfilment know nothing of each other's workflows).

With four services and three compensation triggers, choreography makes the flow implicit across consumers: cyclic event dependencies and the question "who releases the stock?" appear. A workflow engine is the right choice for long, complex flows in production but adds a heavy runtime that cannot run in the Docker-less local stack and would hide the pattern the project teaches.

Costs of the choice: order-service is a coordination hot spot and carries knowledge of the whole flow; it must be highly available (stateless replicas plus persisted state), and idempotent and crash-safe (state and outbox in one transaction). Command and reply topics add topology. Where choreography is used: notification consumes order and fulfilment events and never influences order state (a failed notification never changes an order), and inventory's consumption of `OrderConfirmed` and `OrderCancelled` is belt and braces beside explicit commands.

Pure domain logic helps both styles: `OrderStateMachine` is a pure class mapping `(status, trigger)` to `TRANSITION`, `NO_OP` or `INVALID`, and `OrderTransitions` is the only code that changes `Order.status` and writes history and audit. This is why duplicates and stale events degrade to no-ops. A subtle detail to mention: `InventoryReserved` arriving for a `CANCELLED` order triggers `ReleaseInventory` and the order stays cancelled (`lateReservationAfterCancel`).

**Trade-offs and failure modes.**
- Orchestrator code can become a god class; keep decision rules in the state machine and I/O in messaging helpers.
- Orchestration couples participants to the orchestrator's command vocabulary.
- Choreography is cheaper for two or three steps; the pain grows with steps and compensation paths.

**Likely follow-ups.**
- When would you adopt Temporal? Long-running flows with human steps, timers and heavy retry logic.
- How do you avoid the orchestrator becoming a bottleneck? Stateless replicas, short transactions, partitioned by orderId.
- Is the orchestrator's DB a single point of failure? Per-service database; use Multi-AZ PostgreSQL in production.

### Q44. Scenario: Orders are stuck in VALIDATED for 10 minutes. What is wrong and how do you fix it?
**Type:** Scenario   **Level:** Advanced   **Topic:** Saga troubleshooting

**Short answer.** `VALIDATED` means `ReserveInventory` was queued but no reply has been applied. With recovery enabled the saga should have been re-driven about every minute and failed after five attempts (roughly five to six minutes), so ten minutes in `VALIDATED` suggests the recovery job is not running or the command and reply path is blocked in a way recovery cannot fix.

**Detailed answer.** Reasoning from the code: `SagaRecoveryJob` runs every 10 s (`oms.saga.recovery-interval-ms`) and re-drives sagas in `RUNNING` or `COMPENSATING` state whose `updated_at` is older than 60 s (`findStalled`). In `OrderSagaOrchestrator.recover`, case `VALIDATED` increments attempts (which refreshes `updated_at`), re-sends `ReserveInventory` and logs a `RETRIED` step; once `attempts >= max-attempts` (5) `exhaust` moves the order to `FAILED` and sends a defensive `ReleaseInventory`. So a healthy system leaves `VALIDATED` within about 6 minutes. Staying longer implies one of: recovery disabled (`oms.saga.recovery-enabled=false`), the scheduler threads starved (shared pool of 4), the order-service instances down, a saga whose state is not `RUNNING` (inspect it), or the re-sent command never leaving.

Diagnosis (runbook 01, `docs/runbooks/01-orders-stuck-processing.md`):

```bash
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/admin/saga/<orderId>
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/order/messaging
```

Read `state`, `currentStep`, `attempts`, `lastError`, and the steps. Then walk the tree: (1) order-service `outboxPending` high means the relay or broker is the problem (commands never reached Kafka; check `OutboxRelay` logs and runbook 02). (2) inventory consumer lag high or the record in `oms.inventory.commands.v1.DLT` (read `x-exception-*`: poison versus transient). (3) the reservation exists but `InventoryReserved` was never applied by order-service: check lag on `oms.inventory.events.v1` and order-service's DLT; stale or unknown-order events are ignored and counted (`oms.saga.events.ignored`), not dead-lettered. (4) inventory's slot for the order exists as `FAILED` or a `RELEASED` tombstone (an earlier cancel) and the event was not delivered; query `GET /api/v1/inventory/reservations?orderId=`.

SQL (adapt names): `select order_id, state, current_step, attempts, last_error, updated_at from saga_instances where state not in ('COMPLETED','FAILED','COMPENSATED','ABORTED') and updated_at < now() - interval '2 minutes'`; note the real saga states are `RUNNING, COMPENSATING, COMPLETED, ABORTED, COMPENSATED, FAILED, NEEDS_ATTENTION`.

Mitigation: fix the dependency first (broker, inventory, DB); the recovery job then re-drives automatically; for single orders use `POST /api/v1/admin/orders/{id}/retry` (resets attempts, audited); for cross-service drift run `POST /api/v1/admin/reconciliation/run`; do not update `orders.status` by SQL. Prevention: alerts `OmsSagasStuck` and `oms_saga_active` growth, `OmsOutboxBacklog`.

**Trade-offs and failure modes.**
- Retry spacing of 60 s means customers wait minutes before failure; tune per SLO.
- Manual retry on a terminal order is 409; the customer re-orders.
- A re-sent command is safe only because the receiver is idempotent.

**Likely follow-ups.**
- Why would a duplicate command not double-reserve? Unique `order_id` slot returns the original outcome.
- Which log line proves a re-send? The step log `RETRIED` entry and `Saga of order ... re-driven by recovery job`.
- Could a non-RUNNING saga hide? Yes; inspect `state` first.

### Q45. Scenario: A customer cancels while the reservation is still in flight. How does the system avoid leaking stock?
**Type:** Scenario   **Level:** Advanced   **Topic:** Cancel versus reserve race

**Short answer.** If the cancel wins, the order becomes `CANCELLED` immediately and a late `InventoryReserved` triggers a compensating `ReleaseInventory` while the order stays cancelled; inventory also writes a `RELEASED` tombstone when a release arrives before a reservation, so a late reserve cannot take stock. Concurrent cancel and saga events are serialised by optimistic locking with re-read.

**Detailed answer.** Timeline: order is `VALIDATED`, `ReserveInventory` is on its way. The customer calls `POST /orders/{id}/cancel`. `OrderCommandService.cancel` runs in `RetryingTransactions`, loads the order (404 if not the owner), and `OrderSagaOrchestrator.cancel` applies `CANCEL_REQUESTED` strictly. From `CREATED` or `VALIDATED` the order goes to `CANCELLED` immediately, the saga is `ABORTED`, and `OrderCancelled` is appended via the outbox. No `ReleaseInventory` is sent yet because order-service does not know a reservation exists.

Now `InventoryReserved` arrives. `onInventoryReserved` records the reservation id, sees `order.getStatus() == CANCELLED`, and calls `lateReservationAfterCancel`: it sends `ReleaseInventory(CANCELLATION)`, counts a compensation (`oms.saga.compensations`, reason `LATE_RESERVATION`) and audits `LATE_RESERVATION_RELEASED`; the order stays `CANCELLED` (it is not resurrected). Inventory releases and answers `InventoryReleased`; `onInventoryReleased` marks `reservation_released`.

Inventory side of the same race: `OrderCancelled` is also consumed by inventory (release with reason `CANCELLATION`, emitting only when it actually releases). If the release is processed before the reserve, `ReservationClaims.claimReleasedTombstone` inserts a `RELEASED` row for that `orderId`, so a subsequent `ReserveInventory` finds the slot taken and returns the original outcome without touching stock. Goods already shipped (`shippedAt` set) are not released (no-op plus warning).

Concurrency inside order-service: `CustomerOrder` and `SagaInstance` have `@Version`; if a cancel collides with a saga event, `RetryingTransactions` (5 attempts, short random backoff) re-executes from scratch so the retry sees the winner's state. A Kafka handler that loses simply fails and the container retries it.

Known limitation (order-service README section 15): a cancel arriving while fulfilment is finishing moves the order to `COMPENSATION_PENDING`; a later `FulfillmentCompleted` is ignored by the state machine, so inventory may still ship stock that order-service has released. Closing that needs a fulfilment-side cancel. State this honestly; it is a cross-service race that eventual consistency does not solve by itself.

Verification: Kafka integration tests cover cancellation with compensation, and saga tests cover late reservation and optimistic-lock retry; the cross-service race with real inventory and fulfilment services was tested against stand-ins.

**Trade-offs and failure modes.**
- Cancel is accepted before the reservation is known, so stock is briefly held and then released.
- Tombstones accumulate in `inventory_reservations`.
- Optimistic retries add latency under contention.

**Likely follow-ups.**
- Why not block cancel until the reservation answers? It would couple UX to downstream latency.
- What does the customer see? `CANCELLED` at once (or `COMPENSATION_PENDING` after a reservation).
- How do you detect leaked reservations? Reconciliation and the TTL expiry job.

### Q46. Scenario: A reservation expires before the order is confirmed. What happens, and how do you tune it?
**Type:** Scenario   **Level:** Advanced   **Topic:** Reservation TTL and late commit

**Short answer.** The inventory expiry job moves an overdue `RESERVED` reservation to `EXPIRED`, returns the stock and emits `InventoryReleased{EXPIRED}`; order-service then moves an order still in `VALIDATED` or `INVENTORY_RESERVED` to `FAILED`. A confirmation arriving after expiry is refused, counted and left to reconciliation; guarded UPDATEs ensure exactly one of expiry and confirm wins.

**Detailed answer.** Lifecycle: `RESERVED` holds stock in `stock.reserved` with `expires_at` (default 900 s via `oms.inventory.reservation-ttl-seconds`, capped by `max-reservation-ttl-seconds`; the `ReserveInventory` command may carry `reservationTtlSeconds`). `OrderConfirmed` changes it to `COMMITTED` and clears the TTL. `ReservationExpiryJob` runs every 30 s (`expiry-scheduler-interval-ms`), selects ids with `ix_reservations_status_expiry (status, expires_at)`, and expires up to `expiry-batch-size` (100) per pass.

Race handling: expiry and confirm both use status-guarded UPDATEs (`... WHERE status = 'RESERVED'`), so only one changes the row, on one or several instances. If `OrderConfirmed` loses, inventory does not re-reserve: it logs, increments `oms.inventory.reservation.late_commit` and leaves repair to reconciliation, because blindly re-reserving could oversell. In order-service, `onInventoryReleased` with reason `EXPIRED` and status `VALIDATED` or `INVENTORY_RESERVED` applies `RESERVATION_EXPIRED`, sets the saga to `FAILED` and publishes `OrderFailed` with the reason "Inventory reservation expired before the order was confirmed".

Why it can happen in a healthy system: the saga confirms immediately on `InventoryReserved`, so expiry mostly signals trouble, such as a long consumer backlog (the reply sat in lag longer than 15 minutes) or order-service downtime. In that case the order fails visibly rather than shipping stock that was already released.

Tuning: TTL must exceed the 99th percentile of reserve-to-confirm delay including backlog (use consumer lag SLOs) but be short enough that abandoned reservations do not starve stock; the expiry interval adds up to 30 s of slack. Watch `oms.inventory.reservations{outcome=expired}` and `late_commit`; a non-zero `late_commit` rate means TTL is too short or the pipeline too slow. Alerts: `OmsConsumerLagHigh`, `OmsSagasStuck`.

Edge: `ReleaseInventory` and `InventoryReleased{reason:EXPIRED}` are also the defensive path after saga exhaustion. Reply duplicates are harmless.

```sql
select r.id, r.order_id, r.status, r.expires_at from inventory_reservations r
 where r.status = 'RESERVED' and r.expires_at < now();   -- should be near empty if the job runs
```

Honest status: expiry logic is tested on H2 (`ReservationLifecycleTest`) and PostgreSQL scenarios; the interplay with real backlog was not load-tested.

**Trade-offs and failure modes.**
- Short TTL frees stock quickly but fails slow orders; long TTL holds stock for abandoned orders.
- The expiry scan is per instance and relies on guarded updates, not on a lock.
- A late commit leaves a confirmed-looking order without stock until reconciliation or failure handling acts.

**Likely follow-ups.**
- Why not extend the TTL on commit failure? Re-reserving could oversell; refusing is safer.
- Who notices a late commit? The counter, then reconciliation.
- Does expiry emit an event? Yes, `InventoryReleased` with `EXPIRED`.

### Q47. What does `ReconciliationService` do, what can it not see, and what invariants hold at all times versus eventually?
**Type:** Conceptual   **Level:** Advanced   **Topic:** Reconciliation and invariants

**Short answer.** It scans order-service's own saga data for inconsistencies with the other services (a terminal order whose reservation was never confirmed released, a `CONFIRMED` order without fulfilment progress, a flagged compensation) and repairs them by re-issuing idempotent commands. It cannot read inventory's or fulfilment's real state.

**Detailed answer.** Triggers: a scheduled job (`oms.reconciliation.interval-ms`, default 5 minutes; only items older than `min-age-seconds`, 120, so in-flight work is left alone) and `POST /api/v1/admin/reconciliation/run` (ADMIN) returning `{examined, repaired, details[]}`; results are audited (`RECONCILIATION_RUN`, `ORDER_RECONCILED`). Implementation facts from the class Javadoc: it knows only what other services told order-service (`reservation_id` from `InventoryReserved`, `InventoryReleased`, `FulfillmentStarted`) and repairs by asking again, never by touching their data. Index `ix_saga_reservation (reservation_released, state)` supports the first query.

The blind spot is real: an inconsistency that never produced any message at order-service (inventory reserved but the reply was lost and nothing else referred to it) is bounded by inventory's own TTL expiry, not detected by reconciliation. The shared contract describes a broader check (including a cancelled order with a `RESERVED` reservation); the README narrows what the implementation can see, so quote the README.

Invariants. Local, always, enforced by transactions and constraints: never `reserved > on_hand` (conditional UPDATE plus CHECK constraints), one reservation per order (unique `order_id`), valid order transitions (state machine, `InvalidStateTransitionException` 409), state change plus history plus audit plus outbox in one transaction. Cross-service, eventual: stock released for terminal orders, shipments match confirmed orders, notifications match events. These are bounded by retries, TTLs, the recovery job and reconciliation, and there is no claim of global consistency at any instant.

Operating it: run on a schedule, alert on repair counts, treat a rising `repaired` as a symptom of an upstream bug rather than normal. Semantic compensation has limits (a sent notification cannot be recalled), which is why notification is outside the compensable path.

Also state what has not been verified: reconciliation was tested against stand-in services and embedded Kafka (`ReconciliationTest`, Kafka integration tests), not against the real inventory and fulfilment services together.

**Trade-offs and failure modes.**
- A repair that re-sends commands can fight a slow but still-working flow; `min-age` reduces it.
- Reconciliation hides bugs if nobody watches its output.
- It runs on every replica (duplicate repair attempts, harmless but wasteful).

**Likely follow-ups.**
- Why not let reconciliation query inventory? Violates data ownership; it would need an inventory API.
- How do you prove invariants in production? Nightly read-only invariant queries with alerts (runbook 03).
- Is reconciliation a substitute for idempotency? No, it is a safety net.

### Q48. Scenario: Fulfilment fails after the order is confirmed. How does the platform compensate, and what do the customer and operator see?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Compensation after confirmation

**Short answer.** Fulfilment retries internally, then publishes `FulfillmentFailed`; order-service moves the order from `CONFIRMED` to `COMPENSATION_PENDING`, sends `ReleaseInventory{FULFILLMENT_FAILURE}`, and on `InventoryReleased` ends the order as `FAILED`. Operators can re-open a stuck compensation; the customer sees intermediate statuses honestly.

**Detailed answer.** Fulfilment-service attempts the work (`oms.fulfillment.max-attempts`, default 3; the shipping city `FAILTOWN` is a deterministic failure hook) and publishes `FulfillmentFailed{orderId, fulfillmentId, reason, attempts}` exactly once after the last attempt. In order-service `onFulfillmentFailed` calls `failFulfillment`: apply `FULFILLMENT_FAILED` (CONFIRMED to `COMPENSATION_PENDING`), log the FULFILL step as failed, call `saga.startCompensation(FULFILLMENT_FAILURE)` and append `ReleaseInventory` through the outbox, in one transaction, and count `oms.saga.compensations`.

Inventory releases the reservation (it is `COMMITTED`, not yet shipped: `shippedAt` is null), returns `reserved` and answers `InventoryReleased`. `onInventoryReleased` sees `COMPENSATION_PENDING`; because the saga reason is `FULFILLMENT_FAILURE` (not cancellation) it applies `RELEASED_AFTER_FAILURE`, sets the saga to `FAILED` and the order ends `FAILED` with the original reason, and `OrderFailed` is published; notification emails the customer from the event (simulated).

If release does not complete: `SagaRecoveryJob` re-sends `ReleaseInventory` for `COMPENSATION_PENDING` (up to 5 attempts), then the saga is flagged `NEEDS_ATTENTION` (order stays `COMPENSATION_PENDING`, alert counter `oms.saga.alerts`), re-opened by `POST /api/v1/admin/orders/{id}/retry` or reconciliation. Operators also see `GET /api/v1/admin/orders/failed`, which includes `COMPENSATION_PENDING` orders with no progress for `stuck-after-seconds`. A manual fulfilment retry exists (`POST /api/v1/admin/fulfillments/{id}/retry`, only for `FAILED` fulfilments); a later success publishes `FulfillmentCompleted` without a second `FulfillmentStarted`, and order-service must tolerate a late event, which the state machine does by ignoring it for an order no longer `CONFIRMED`.

Customer view: `CONFIRMED`, then `COMPENSATION_PENDING`, then `FAILED` with a reason, via polling and the history timeline (`saga-and-consistency.md` section 6). The platform does not hide intermediate states.

```
CONFIRMED --FulfillmentFailed--> COMPENSATION_PENDING --InventoryReleased(FULFILLMENT_FAILURE)--> FAILED
```

Why compensation, not rollback: other services have committed (inventory reservation committed, events published), so undo means a new forward action with its own trace (`RELEASED`, `stock_movements`, audit rows).

**Trade-offs and failure modes.**
- Money or payment steps would need their own compensations (refund); none exist in this demo.
- `FAILED` is terminal; the customer places a new order.
- Notification is outside the compensable path: the "confirmed" email already sent stays sent.

**Likely follow-ups.**
- Why not retry fulfilment forever? Bounded attempts keep stock from being held indefinitely and surface failures.
- What if the reservation already shipped? Release is a no-op plus warning; that is the cancel-versus-ship limitation.
- How do you test it? The `FAILTOWN` city drives a deterministic failure in Kafka integration tests and the local stack.

