# Spring Boot 3.5 and Spring Cloud: Auto-configuration, Transactions, Gateway and Resilience4j

This chapter covers the Spring half of the interview. The platform runs on Spring Boot 3.5.16 (Spring Framework 6.2, Jakarta EE namespace, Hibernate 6), Spring Cloud 2025.0.0 (Spring Cloud Gateway on WebFlux), springdoc-openapi 2.8.17 and Resilience4j 2.2.0 (`services/pom.xml`). Java 17 is the baseline.

## Big picture and kid-friendly analogy

Think of a school with a very organised front office.

* **Auto-configuration** is a smart toy box. When you open it and put a Lego motor in, the box notices "ah, you have a motor, so here is the matching gear set already built". If you add your own gear set, the box politely keeps its hands off. That is `@ConditionalOnClass` and `@ConditionalOnMissingBean`.
* **Dependency injection** is a school lunch tray delivered to your desk. You do not walk to the kitchen and cook; you say what you need (constructor parameters) and the school brings it.
* **A proxy** is the school secretary. When you want to talk to the principal, the secretary first writes your name in the visitor book, starts a timer and, when you leave, closes the book (a transaction). You must go through the secretary. If you shout across the room from inside the principal's office to talk to the principal again (a method calling another method on `this`), nobody writes anything in the book. That is the self-invocation pitfall.
* **`@Transactional`** is an "all or nothing" cookie jar: either every cookie you ordered goes in the bag, or the whole order is cancelled.
* A **circuit breaker** is the fuse box at home. If a toaster keeps tripping the fuse, you stop plugging it in for a little while instead of burning the house down. A **retry** is knocking again, politely, a couple of times. A **rate limiter** is the turnstile at a playground slide.
* The **gateway** is the single front door with a security guard, a visitor badge printer and a map to the classrooms.

| Analogy | Spring concept |
|---|---|
| Smart toy box | Auto-configuration (`AutoConfiguration.imports`, conditions) |
| Lunch tray delivery | Constructor injection, the `ApplicationContext` |
| Secretary with a visitor book | AOP proxy (JDK/CGLIB) applying `@Transactional` |
| Cookie jar | Transaction boundary, rollback rules |
| Fuse box / knocking / turnstile | Resilience4j circuit breaker / retry / rate limiter |
| Front door | Spring Cloud Gateway routes and filters |
| Visitor badge | JWT validated at the edge and again in services |

## Beginner tier

### Example 1: configuration properties, environment variables and profiles

Settings live in `application.yml`, bound to typed classes, with environment variables as overrides. In `services/order-service/src/main/resources/application.yml`:

```yaml
oms:
  saga:
    stuck-after-seconds: ${OMS_SAGA_STUCK_AFTER_SECONDS:60}
    max-attempts: ${OMS_SAGA_MAX_ATTEMPTS:5}
    recovery-enabled: ${OMS_SAGA_RECOVERY_ENABLED:true}
    recovery-interval-ms: ${OMS_SAGA_RECOVERY_INTERVAL_MS:10000}
    reservation-ttl-seconds: ${OMS_SAGA_RESERVATION_TTL_SECONDS:900}
```

They bind into a `@ConfigurationProperties` class (`services/order-service/src/main/java/com/enterprise/oms/order/config/SagaProperties.java`):

```java
/** {@code oms.saga.*}: orchestration, retry and recovery tuning. */
@ConfigurationProperties(prefix = "oms.saga")
public class SagaProperties {

    /** A saga with no progress for this long is re-driven by the SagaRecoveryJob (contract default 60). */
    private long stuckAfterSeconds = 60;
```

`OrderServiceConfig` activates it with `@EnableConfigurationProperties`. Relaxed binding maps `OMS_SAGA_MAX_ATTEMPTS` to `oms.saga.max-attempts`, which is how the Kubernetes ConfigMap (`infrastructure/kubernetes/base/configmap.yaml`) configures services without touching files. Prefer `@ConfigurationProperties` over scattered `@Value`: it is typed, validated (`@Validated`), documented and testable.

Profiles select blocks of the same file using `spring.config.activate.on-profile`. The order-service file has `dev` (seed data, leak detection), `local`, `prod` and `json-logs` blocks. The `prod` block deliberately has no defaults for secrets:

```yaml
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: ${OMS_DB_URL}
    username: ${OMS_DB_USER}
    password: ${OMS_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
```

An unresolved placeholder fails startup, which is the point: a mis-deployed prod pod crashes loudly instead of running with a default password. The ConfigMap base also sets `SPRING_PROFILES_ACTIVE: "json-logs"` only, so `dev` (which enables the token endpoint and swagger) can never be the accidental default in a cluster.

### Example 2: constructor injection, stereotypes and method security

Beans are discovered with stereotypes and wired through constructors. `OrderController` (`services/order-service/src/main/java/com/enterprise/oms/order/api/OrderController.java`) shows the idiom:

```java
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Create, search, inspect and cancel orders")
public class OrderController {
    ...
    public OrderController(OrderCommandService commands, OrderQueryService queries, PageRequestFactory pages,
                           OrderRateLimiter rateLimiter, RateLimitProperties rateLimitProps) {
```

A single constructor needs no `@Autowired`. Constructor injection keeps dependencies `final`, makes the class testable with plain `new`, and makes circular dependencies fail fast at startup instead of hiding them behind field injection. Authorisation sits next to the endpoint: `@PreAuthorize(ROLES)` where `ROLES = "hasAnyRole('CUSTOMER','OPERATOR','ADMIN')"`, switched on by `@EnableMethodSecurity` in the platform security auto-configuration. Interview follow-up: `@PreAuthorize` is itself proxy based, so it shares the self-invocation limitation described below.

### Example 3: Actuator, health groups and Kubernetes probes

`management.endpoints.web.exposure.include` is limited to `health,info,prometheus`. In the order-service file:

```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health:
    probes.enabled: true
    group:
      readiness.include: readinessState,db
```

Spring Boot exposes `/actuator/health/liveness` and `/actuator/health/readiness`. Readiness includes the database, so a pod with no DB connection is removed from the Service endpoints but not restarted; liveness stays independent of dependencies so a database outage does not trigger a restart storm. The Deployment wires them (`infrastructure/kubernetes/base/order-service.yaml`): `startupProbe` on liveness for three minutes (JVM plus Flyway on first boot), `readinessProbe` on readiness, `livenessProbe` on liveness. `show-details` is `never` by default (the `local` profile sets `always`), and `server.error.include-*` properties from `services/platform-common/src/main/resources/META-INF/oms-defaults.properties` stop stack traces and messages leaking in error pages. The gateway applies the same idea in a deliberate way: its readiness does **not** depend on upstream services (`gateway/README.md`), because a gateway that restarts when a backend is down only makes things worse.

## Intermediate tier

### Example 1: `@Transactional`, proxies and the self-invocation pitfall

Spring wraps a bean in a CGLIB proxy (the Boot default is `proxyTargetClass=true`) that begins a transaction before the method, commits after it returns and rolls back on an unchecked exception. Three consequences interviewers love:

1. **Self-invocation bypasses the proxy.** `this.handle()` called from another method of the same class never crosses the proxy, so `@Transactional`, `@Async`, `@Cacheable` and `@PreAuthorize` on `handle` are silently ignored. The repository documents this as a code-review scenario in `docs/code-review/03-transactional-self-invocation.md`. That document is a deliberately flawed pull request used for teaching (its `InventoryEventConsumer` class does not exist in the code base), but the mechanism is real. Its fix options, safest first, are: annotate the public entry point called from outside; move the method to another bean; or use `TransactionTemplate`.
2. **Rollback rules**: by default only `RuntimeException` and `Error` roll back. A checked exception commits unless you declare `rollbackFor`.
3. **Propagation**: the default `REQUIRED` joins an existing transaction. This repository uses `MANDATORY` to make "must be inside a transaction" a runtime guarantee. `OutboxService.append` (`services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxService.java`) and the order state-transition service both do:

```java
@Transactional(propagation = Propagation.MANDATORY)
public Decision apply(CustomerOrder order, OrderTrigger trigger, String reason, String actor) {
    Decision d = OrderStateMachine.decide(order.getStatus(), trigger);
```

If a developer calls `apply` outside a transaction it throws `IllegalTransactionStateException` instead of half-applying a state change. The source is `services/order-service/src/main/java/com/enterprise/oms/order/service/OrderTransitions.java`.

The code also avoids self-invocation structurally by using **programmatic** transactions where one class needs several boundaries. `OrderCommandService.create` is intentionally not `@Transactional`; it loops over a `TransactionTemplate`:

```java
for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
    try {
        return tx.execute(status -> doCreate(request, scope, key, hash));
    } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
```

The source is `services/order-service/src/main/java/com/enterprise/oms/order/service/OrderCommandService.java`. A retry has to begin a fresh transaction after the failed one rolled back, which an annotation on the same method cannot do. `RetryingTransactions` generalises the idea:

```java
public <T> T run(Supplier<T> work) {
    ConcurrencyFailureException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
            return template.execute(status -> work.get());
        } catch (ConcurrencyFailureException e) {
```

Another subtle design choice: `OrderSagaOrchestrator.validate` is **not** transactional because it makes a REST call to inventory. It reads inside one transaction, calls out with no connection held, then applies the result in a second transaction. Holding a DB connection across network I/O is how connection pools get exhausted (`docs/database/slow-query-lock-contention-pool-exhaustion.md`).

### Example 2: Spring Data JPA, `@Version`, `@Modifying` queries and plain JDBC

Inventory has three ways to touch `stock`, each chosen for a reason (ADR-0006). The entity carries `@Version` for optimistic locking on rare admin edits (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/domain/Stock.java`). The hot path is a conditional update (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/StockRepository.java`):

```java
@Modifying(flushAutomatically = true)
@Query("update Stock s set s.reserved = s.reserved + :q, s.version = s.version + 1, s.updatedAt = :now "
        + "where s.productId = :id and s.onHand - s.reserved >= :q")
int tryReserve(@Param("id") UUID productId, @Param("q") int quantity, @Param("now") Instant now);
```

Points to know: `@Modifying` queries bypass the persistence context, so a managed `Stock` loaded earlier in the same transaction is stale afterwards (hence `flushAutomatically` to push pending changes first, and a read-only `snapshot` projection for reads instead of cached entities); the return value is the row count, which is the business signal (0 means insufficient stock); and bumping `version` in the SQL makes JPA's optimistic check notice concurrent changes.

Sometimes Spring Data is the wrong tool. `ReservationClaims` (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/ReservationClaims.java`) uses `JdbcTemplate` and a JDBC savepoint to claim a unique slot:

```java
Connection con = DataSourceUtils.getConnection(dataSource);
try {
    Savepoint savepoint = con.setSavepoint();
    try {
        jdbc.update(sql, args);
        con.releaseSavepoint(savepoint);
        return true;
    } catch (DuplicateKeyException duplicate) {
        con.rollback(savepoint);
        return false;
    }
```

The class Javadoc explains why: a failed JPA statement marks the Hibernate transaction rollback-only, and on PostgreSQL a failed statement aborts the whole transaction unless it is wrapped in a savepoint. `DataSourceUtils.getConnection` joins the connection already bound to the current Spring transaction, which is what keeps this inside the same unit of work as the `processed_events` insert.

### Example 3: avoiding N+1 and the open-session-in-view trap

`spring.jpa.open-in-view: false` is set for every service (`services/platform-common/src/main/resources/META-INF/oms-defaults.properties` and the order-service file), so lazy loading cannot happen in the web layer and a service method must load what it needs. `OrderQueryService.page` (`services/order-service/src/main/java/com/enterprise/oms/order/service/OrderQueryService.java`) shows the two-step fetch that avoids both N+1 and in-memory paging of a collection fetch join:

```java
Page<CustomerOrder> page = orders.findAll(spec, stable);
List<UUID> ids = page.getContent().stream().map(CustomerOrder::getId).toList();
Map<UUID, CustomerOrder> withItems = ids.isEmpty() ? Map.of()
        : orders.findAllWithItemsByIdIn(ids).stream().collect(Collectors.toMap(CustomerOrder::getId, Function.identity()));
```

Step one pages the parent table; step two fetch-joins items for exactly those ids in one query. `hibernate.default_batch_fetch_size: 50` is a second safety net, and a test counts statements. The class is annotated `@Transactional(readOnly = true)`, which lets Hibernate skip dirty checking and lets the pool route to a read replica if one exists.

## Advanced tier

### Example 1: platform-common as a custom starter (auto-configuration done properly)

`services/platform-common` is a library, not a service, and every service gets outbox, idempotency, Kafka templates, JWT security and RFC 7807 errors by simply depending on it. The registration file `services/platform-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists eight classes (`OmsCoreAutoConfiguration`, `OmsPersistenceAutoConfiguration`, `OmsKafkaAutoConfiguration`, `OmsOutboxAutoConfiguration`, `OmsMessagingAutoConfiguration`, `OmsAuditAutoConfiguration`, `OmsWebAutoConfiguration`, `JwtSecurityAutoConfiguration`). Each follows the conventions you should be able to recite (`OmsOutboxAutoConfiguration`):

```java
@AutoConfiguration(after = {OmsKafkaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class,
        TransactionAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnClass({OutboxEvent.class, KafkaTemplate.class})
@ConditionalOnProperty(prefix = "oms.outbox", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OmsOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxService outboxService(OutboxEventRepository repository, ObjectMapper mapper,
```

* `@AutoConfiguration(after=...)` orders it relative to Boot's own auto-configurations, so the beans it needs (JPA repositories, transaction manager, ObjectMapper) exist first.
* `@ConditionalOnClass` makes it a no-op when the dependency is absent; `@ConditionalOnProperty(... matchIfMissing = true)` gives an off-switch (`oms.outbox.enabled=false` in services that never publish).
* `@ConditionalOnMissingBean` is the "back off" rule: a service can define its own bean and win.
* `OmsPersistenceAutoConfiguration` uses `@AutoConfigurationPackage(basePackages = {...})` to register the library's entity and repository packages, so a plain `@SpringBootApplication` service sees `OutboxEvent` and `ProcessedEvent` without `@EntityScan`. Its Javadoc warns that adding `@EnableJpaRepositories` in a service replaces this and must then list those packages.
* `OmsDefaultsEnvironmentPostProcessor` (registered in `services/platform-common/src/main/resources/META-INF/spring.factories`) adds `services/platform-common/src/main/resources/META-INF/oms-defaults.properties` as the **lowest-precedence** property source (`addLast`). Platform defaults such as `spring.jpa.open-in-view=false`, `spring.flyway.locations=classpath:db/common,classpath:db/migration` and `server.shutdown=graceful` apply everywhere and any service can override them.

The reactive gateway shows the flip side. It depends on platform-common only for a few helper classes and must switch off the servlet, JPA and Kafka pieces. `gateway/src/main/resources/application.yml`:

```yaml
spring:
  autoconfigure:
    exclude:
      - com.enterprise.oms.common.autoconfigure.OmsCoreAutoConfiguration   # ProblemWriter needs the servlet API
      - com.enterprise.oms.common.autoconfigure.OmsPersistenceAutoConfiguration
      - com.enterprise.oms.common.autoconfigure.OmsKafkaAutoConfiguration
```

(The file excludes all eight classes.) Lesson: a shared library that is a starter couples every consumer to its conditions; tell people how to opt out, and run `--debug` to read the conditions evaluation report when something is unexpectedly missing.

### Example 2: Spring Cloud Gateway routes, filters and ordering

The route table is Java configuration, not YAML (`gateway/src/main/java/com/enterprise/oms/gateway/config/RoutesConfig.java`):

```java
.route("order-service", r -> r.path(
                "/api/v1/customers", "/api/v1/customers/**",
                "/api/v1/orders", "/api/v1/orders/**",
                "/api/v1/admin/orders/**", "/api/v1/admin/saga/**", "/api/v1/admin/reconciliation/**")
        .filters(breaker("order")).uri(order))
...
private static Function<GatewayFilterSpec, org.springframework.cloud.gateway.route.builder.UriSpec> breaker(String svc) {
    return f -> f.circuitBreaker(c -> c.setName(svc).setFallbackUri("forward:/fallback/" + svc));
}
```

Concepts: a **route** = id + predicates (here `path`) + filters + target URI. **Route filters** (here `circuitBreaker`, `rewritePath` for `/api/v1/ops/{svc}/**`) apply per route. Cross-cutting concerns that must run on every request are Spring WebFlux `WebFilter`s with explicit order values in `EdgeFilters.Order`: correlation id at `HIGHEST_PRECEDENCE`, access log, header sanitiser, path guard, then Spring Security (order -100), and only then the rate limiter (-60) and body-size limit (-50). The ordering is the design: the rate limiter runs after authentication so it can key by the validated JWT subject and a forged token cannot create buckets. The fallback is a controller that is only reachable by an internal forward (it checks the `CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR`), mapping connect failures, timeouts and open circuits to 503 `UPSTREAM_UNAVAILABLE` with `Retry-After: 5`. Upstream 4xx and 5xx responses pass through unchanged and do not trip the breaker. There is deliberately **no retry filter** at the edge, because `POST /orders` is not safely repeatable at a layer that does not know about idempotency keys (`RoutesConfig` Javadoc).

### Example 3: Resilience4j circuit breaker, retry and where the rate limiter lives

The only synchronous service-to-service call (order to inventory validation) is protected by annotations on `RestInventoryClient` (`services/order-service/src/main/java/com/enterprise/oms/order/saga/RestInventoryClient.java`):

```java
@Override
@Retry(name = "inventory")
@CircuitBreaker(name = "inventory")
public ValidationResult validate(List<EventPayloads.QuantityItem> items) {
```

and configured in `application.yml`:

```yaml
resilience4j:
  retry:
    instances:
      inventory:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - com.enterprise.oms.order.saga.InventoryUnavailableException
  circuitbreaker:
    instances:
      inventory:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions:
          - com.enterprise.oms.order.saga.InventoryUnavailableException
        ignore-exceptions:
          - com.enterprise.oms.order.saga.InventoryCallException
```

Reading it like an expert: the Resilience4j Spring aspect order is Retry outermost, then CircuitBreaker, then RateLimiter, TimeLimiter and Bulkhead (`Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(fn)))))`), so each retry attempt is counted by the breaker. The code classifies failures: `RestInventoryClient` turns 5xx, 429, 408, timeouts and connection errors into `InventoryUnavailableException` (retried and recorded) and other 4xx into `InventoryCallException` (a caller bug, not retried and **ignored** by the breaker so a bad request never opens the circuit). When the breaker is open, Resilience4j throws `CallNotPermittedException`, which is not in `retry-exceptions`, so there is no retry storm against an open circuit; the orchestrator catches the `RuntimeException`, counts a saga attempt, and the recovery job retries later. Finally, only idempotent operations should be retried; `validate` is a read-style POST, which is why it qualifies. `InventoryClientResilienceTest` exercises the real client against a fake HTTP server (retry, timeout, circuit breaker, service token).

Rate limiting is **not** done with Resilience4j's `RateLimiter` in this repository. Both limiters are hand-written token buckets (`InMemoryOrderRateLimiter` in order-service and `InMemoryTokenBucketLimiter` in the gateway) with a Redis variant switched on by `oms.redis.enabled`, degrading to in-memory when Redis fails. If asked how you would use the library, this illustrative configuration (not in the repo) is the idiom:

```yaml
# illustrative only, not in the repository
resilience4j:
  ratelimiter:
    instances:
      inventory:
        limit-for-period: 50
        limit-refresh-period: 1s
        timeout-duration: 0
```

### Example 4: why there is no Eureka and no Config Server

This is a favourite "tell me about a trade-off" question. Service discovery here is the platform's job, not the application's:

* Gateway routes are static properties (`oms.gateway.routes.*.uri`, env `OMS_GATEWAY_ROUTES_ORDER_URI`), and Spring Cloud LoadBalancer is switched off with `spring.cloud.loadbalancer.enabled: false`.
* In Kubernetes the targets are ClusterIP Service names from the ConfigMap (`infrastructure/kubernetes/base/configmap.yaml`): `OMS_INVENTORY_BASE_URL: "http://inventory-service:18082"` and `OMS_GATEWAY_ROUTES_ORDER_URI: "http://order-service:18081"`. kube-proxy and DNS balance across ready pods, and readiness probes handle health.
* Configuration is split into ConfigMap (non-secret, `envFrom`) and Secret (`OMS_JWT_SECRET`, DB credentials via `secretKeyRef`) with profile files as defaults, so no Config Server is needed.

Trade-offs to state honestly: you lose client-side load balancing, instance metadata and runtime config refresh without a restart (a Config Server with Spring Cloud Bus can push changes; a ConfigMap change needs a rollout unless you add a reloader); you gain fewer moving parts, no extra stateful component and one fewer failure mode on the startup path. Eureka is a sensible choice when services run outside an orchestrator (VMs, mixed estates) or need rich client-side routing; on Kubernetes it duplicates what the platform already gives you.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Typed config, env overrides, profiles | `services/*/src/main/resources/application.yml`, `services/order-service/src/main/java/com/enterprise/oms/order/config/SagaProperties.java` | `prod` block has no secret defaults |
| Platform auto-configuration | `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/*.java`, `services/platform-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Conditional, ordered, overridable |
| Low-precedence defaults | `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/OmsDefaultsEnvironmentPostProcessor.java`, `services/platform-common/src/main/resources/META-INF/oms-defaults.properties` | Open-in-view off, graceful shutdown |
| `@Transactional` (MANDATORY, readOnly) | `services/order-service/src/main/java/com/enterprise/oms/order/service/OrderTransitions.java`, `services/order-service/src/main/java/com/enterprise/oms/order/service/OrderQueryService.java`, `services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxService.java` | Programmatic `TransactionTemplate` where retries are needed |
| Self-invocation lesson | `docs/code-review/03-transactional-self-invocation.md` | Teaching scenario, not live code |
| Spring Data JPA, `@Version`, `@Modifying` | `services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/StockRepository.java`, `services/inventory-service/src/main/java/com/enterprise/oms/inventory/domain/Stock.java` | Conditional UPDATE is the hot path |
| JdbcTemplate with savepoint | `services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/ReservationClaims.java` | Keeps Kafka transaction usable |
| Actuator, probes | `application.yml`, `infrastructure/kubernetes/base/*.yaml` | Readiness includes `db` |
| Gateway routes and filters | `gateway/src/main/java/com/enterprise/oms/gateway/config/RoutesConfig.java`, `gateway/src/main/java/com/enterprise/oms/gateway/filter/EdgeFilters.java` | No retry, static URIs |
| Resilience4j | `services/order-service/src/main/java/com/enterprise/oms/order/saga/RestInventoryClient.java`, `application.yml` | Retry outside breaker; gateway has its own breaker config |
| Method security and resource server | `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/JwtSecurityAutoConfiguration.java` | Backs off if you define a `SecurityFilterChain` |
| Config and discovery by K8s | `infrastructure/kubernetes/base/configmap.yaml` | No Eureka, no Config Server |

### Demonstration-only vs production-grade

* **Production-grade**: constructor injection everywhere, fail-fast config for prod, no stack traces in error responses, graceful shutdown, probes that do not depend on upstreams (gateway), bounded executor and caches, CSRF disabled only with a documented reason (stateless bearer API), method security plus URL-level rules plus resource-level checks.
* **Demonstration-only**: the JWT is **HS256** with a shared secret from `OMS_JWT_SECRET` (OIDC/JWKS mode exists via `oms.security.jwt.issuer-uri`/`jwk-set-uri`, and the gateway README states it is not covered by tests); the dev token endpoint exists only in profile `dev`; SERVICE tokens are minted from the same shared secret, so any service could impersonate another (production would use mTLS or per-service keys); Redis-backed rate limiting is implemented but not exercised without Redis; the in-memory breaker and rate-limit state is per replica.
* **Not what you would copy blindly**: static route URIs are right on Kubernetes but not for a mixed estate; `ddl-auto: none` plus Flyway is correct, but H2 in PostgreSQL mode is only an approximation of PostgreSQL behaviour (Testcontainers PostgreSQL tests are skipped without Docker).
* **Honest status**: Kubernetes manifests, Kustomize overlays and Terraform are statically validated, not applied to a cluster.

## Common mistakes and gotchas

* `@Transactional` on a private method, on a method called via `this`, on a class instantiated with `new`, or on a method whose exception is caught inside it (no rollback because the proxy never sees the exception).
* Expecting checked exceptions to roll back.
* A long transaction around a REST or Kafka call (connection held while waiting).
* `@Modifying` query followed by reading a stale entity from the persistence context.
* Open-session-in-view left on, hiding N+1 queries and lazy-loading in controllers.
* Defining your own bean and still getting the auto-configured one because the auto-config lacks `@ConditionalOnMissingBean` (or the opposite: your bean name does not match a `name=`-based condition, as with `omsKafkaTemplate`).
* Ordering auto-configurations without `after`, so a needed bean is missing at evaluation time.
* Resilience4j: wrong annotation order assumptions, recording client errors (4xx) as failures so the breaker opens for caller bugs, retrying non-idempotent calls, and forgetting that retry multiplies load on a struggling dependency.
* Counting on the gateway to authorise: edge role rules here are defence in depth and the services stay authoritative.
* Enabling Eureka "because microservices" on Kubernetes, then debugging stale registrations.
* Putting secrets in `application.yml` or ConfigMaps instead of Secrets and environment variables.
* Assuming Java 21 features: `spring.threads.virtual.enabled` needs Java 21 and does nothing on this build.

## Quick-recall cheat sheet

* Boot 3.5.16 = Spring Framework 6.2, Jakarta namespace, Hibernate 6; Spring Cloud 2025.0.0; Resilience4j 2.2.0; springdoc 2.8.17.
* Auto-configuration registered in `services/platform-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; use `@AutoConfiguration(after=)`, `@ConditionalOnClass`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean`; debug with `--debug`.
* `EnvironmentPostProcessor` + `addLast` = overridable platform defaults.
* Constructor injection; one constructor needs no `@Autowired`.
* `@ConfigurationProperties` over `@Value`; relaxed binding `OMS_SAGA_MAX_ATTEMPTS` to `oms.saga.max-attempts`.
* `@Transactional` = proxy; self-invocation, private methods and non-Spring instances bypass it; only unchecked exceptions roll back by default.
* `Propagation.MANDATORY` = "must already be in a transaction"; used for outbox append and state transitions.
* `TransactionTemplate` for programmatic boundaries and retry loops; do not hold a connection across network I/O.
* `@Version` = optimistic lock; conditional `UPDATE ... WHERE` = atomic check-and-set; `@Modifying` bypasses the persistence context.
* `open-in-view: false`, `default_batch_fetch_size`, two-step fetch for paging collections.
* Probes: liveness independent of dependencies; readiness may include the DB; gateway readiness ignores upstreams.
* Gateway: route = predicates + filters + URI; `WebFilter` ordering around Spring Security (-100); rate limit after authentication; circuit breaker with `forward:` fallback; no retry for POST at the edge.
* Resilience4j order: Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead)))); ignore 4xx in the breaker; retry only transient exceptions.
* No Eureka/Config Server: static URIs + Kubernetes Service DNS + ConfigMap/Secret; cost is no runtime refresh and no client-side balancing.
