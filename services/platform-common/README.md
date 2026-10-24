# platform-common

Shared library for every OMS servlet service (order, inventory, fulfillment, notification). Contract:
`docs/architecture/00-shared-contract.md` (sections 5, 7, 10, 12 apply here). Java 17, Spring Boot 3.5.16, no `main` class:
everything is wired by Spring Boot auto-configuration (`META-INF/spring/...AutoConfiguration.imports`), each piece with a
property toggle.

## 1. Using it in a service

```xml
<dependency>
  <groupId>com.enterprise.oms</groupId><artifactId>platform-common</artifactId>
</dependency>   <!-- version managed by services/pom.xml -->
<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
<dependency>
  <groupId>com.enterprise.oms</groupId><artifactId>platform-test-support</artifactId><scope>test</scope>
</dependency>
```

The starters (web, JPA, security + resource server, validation, actuator, Kafka, Flyway, springdoc, Prometheus, OTel,
Resilience4j, logstash encoder) come transitively. Minimal `application.yml`:

```yaml
spring:
  application.name: order-service            # also: Kafka consumer group, metric tag "service", service-token subject
  datasource: { url: jdbc:h2:mem:order_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1 }  # PG url in compose
server.port: 18081
oms:
  messaging.topics: [oms.inventory.events.v1, oms.fulfillment.events.v1]   # topics reported by /ops/messaging
```

Environment: `OMS_JWT_SECRET` (>= 32 bytes, required), `OMS_KAFKA_BOOTSTRAP` (default `localhost:19092`),
`OMS_TRACING_ENABLED` (default false), `OMS_TRACING_SAMPLING`, `OMS_OTLP_ENDPOINT`.

**Defaults contributed** (lowest precedence, `META-INF/oms-defaults.properties`; override freely): Flyway locations
`classpath:db/common,classpath:db/migration`, `open-in-view=false`, `ddl-auto=none`, multipart off, graceful shutdown,
no error detail leakage, actuator exposure `health,info,prometheus` + probes, `management.metrics.tags.service`, tracing off,
Kafka observation on, 4 scheduler threads.

**Do not** add `@EntityScan` / `@EnableJpaRepositories` to a service unless you also list
`com.enterprise.oms.common.{outbox,idempotency,audit}` (they are registered through `@AutoConfigurationPackage`).

### Gateway usage (reactive, Spring Cloud Gateway)
Do not depend on the whole library (servlet + JPA starters would break a reactive app). Either copy nothing and use
`JwtSecret`/`JwtTokenFactory` only, or exclude:
```xml
<dependency><groupId>com.enterprise.oms</groupId><artifactId>platform-common</artifactId>
  <exclusions>
    <exclusion><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></exclusion>
    <exclusion><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></exclusion>
    <exclusion><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></exclusion>
    <exclusion><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></exclusion>
    <exclusion><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></exclusion>
    <exclusion><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId></exclusion>
  </exclusions></dependency>
```
and set `oms.security.enabled=false oms.outbox.enabled=false oms.messaging.enabled=false oms.audit.enabled=false oms.kafka.enabled=false`.
The dev-token endpoint can do: `JwtSecret s = JwtSecret.resolve(props, env); new JwtTokenFactory(s, "oms-dev-issuer", "oms-api").issue(user, roles, customerId, Duration.ofHours(1))`.

## 2. Database: shared Flyway migration
`classpath:db/common/V1__outbox_processed_audit.sql` creates `outbox_events`, `processed_events`, `audit_events`
(portable to PostgreSQL 16 and H2 `MODE=PostgreSQL`). Version ranges: **V1..V8 platform-common**, **V10+ services**, **V1000+ seed**.
The default `spring.flyway.locations` already includes both locations; if a service overrides it, keep
`classpath:db/common,classpath:db/migration` (+ `classpath:db/seed` in `dev`).

## 3. Events (`com.enterprise.oms.common.events`)
* `EventEnvelope<T>` record (eventId, eventType, schemaVersion, occurredAt, correlationId, causationId, aggregateType, aggregateId, producer, payload);
  factories `EventEnvelope.of(...)`, `EventEnvelope.forOrder(type, orderId, producer, correlationId, causationId, payload)`.
* `EventTypes` (type-name constants + `topicFor(type)`, `payloadClassFor(type)`), `Topics` (constants, `ALL`, `ALL_WITH_DLT`, `dlt(topic)`),
  `EventPayloads` (all payload records of contract section 7: `OrderCreated`, `ReserveInventory`, `InventoryReserved`, ... plus `Address`, `PricedItem`, `QuantityItem`, `SkuItem`, `Shortage`, `ReleaseReasons`).
* `EventJson`: tolerant, ISO-date mapper; `EventJson.parseEnvelope(json)` -> `EventEnvelope<JsonNode>` (throws non-retryable `MalformedEventException`), `payloadAs(mapper, env, Type.class)`. Money keeps its scale (`12.50`).

## 4. Transactional outbox (`com.enterprise.oms.common.outbox`)
```java
@Transactional
public void confirm(Order o) {
    o.markConfirmed();                                   // state change
    outbox.append(EventEnvelope.forOrder(EventTypes.ORDER_CONFIRMED, o.getId(), "order-service",
            CorrelationIds.currentOrCreate(), causationEventId,
            new EventPayloads.OrderConfirmed(o.getId(), o.getCustomerId(), email, total)));  // same DB tx
}
// explicit topic/key/headers: outbox.append(Topics.INVENTORY_COMMANDS, orderId.toString(), envelope, Map.of("traceparent", tp));
```
* `OutboxService.append(...)` has `Propagation.MANDATORY`: calling it without a transaction throws, by design.
* `OutboxRelay` polls (`oms.outbox.poll-interval-ms`), publishes oldest-first via `omsKafkaTemplate` (acks=all, idempotent), marks `published_at`.
  A failure (Kafka down) stops the batch, records `attempts/last_error`, and never throws; next poll retries. Delivery is at-least-once.
  Several instances may run (duplicates tolerated by idempotent consumers). Published rows are deleted after `oms.outbox.retention` (default 1h).
* Gauge `oms.outbox.pending`. Properties (`oms.outbox.*`): `enabled` (false for notification-service), `relay-enabled`, `batch-size` (50),
  `poll-interval-ms` (500), `send-timeout-ms` (5000), `retention` (PT1H), `cleanup-enabled`, `cleanup-interval-ms`.
  Producer timeouts: `oms.kafka.max-block-ms|request-timeout-ms|delivery-timeout-ms`, `oms.kafka.enabled`.

## 5. Consuming events (`com.enterprise.oms.common.messaging`, `.idempotency`)
```java
@Component
class InventoryEventsListener {
    private final EventRouter router;
    InventoryEventsListener(EventRouterFactory factory, OrderSagaOrchestrator saga) {
        this.router = factory.router("order-service")          // consumer name stored in processed_events
            .on(EventTypes.INVENTORY_RESERVED, EventPayloads.InventoryReserved.class, (env, p) -> saga.onReserved(p))
            .on(EventTypes.INVENTORY_RESERVATION_FAILED, EventPayloads.InventoryReservationFailed.class, (env, p) -> saga.onFailed(p))
            .build();
    }
    @KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
    void on(ConsumerRecord<String, String> record) { router.route(record); }
}
```
* `route` = parse envelope -> MDC `correlationId` -> unknown type ignored (`oms.events.ignored`) -> `IdempotentEventProcessor.processOnce(consumer, topic, envelope, effect)`:
  inserts `processed_events(consumer,event_id)` in the SAME transaction as the effect; duplicates are skipped and counted (`oms.events.duplicates`).
  If the handler throws, the transaction (including the processed marker) rolls back and the container retries. You can also call `processOnce` directly.
* `omsKafkaListenerContainerFactory`: String deserializers (parse is ours, so bad JSON cannot poison the partition), `AckMode.RECORD`,
  `DefaultErrorHandler` backoff 500ms x2 (max 5s), 4 attempts (`oms.messaging.retry.*`), then `<topic>.DLT` (headers `x-exception-class`, `x-exception-message`,
  `x-original-topic|partition|offset` + Spring's `kafka_dlt-*`). Non-retryable (straight to DLT): `NonRetryableEventException` (extend it / throw it for validation failures),
  `MalformedEventException`, `IllegalArgumentException`, `ConstraintViolationException`, deserialization/conversion errors, `ClassCastException`.
* Metrics (tag `service` added globally): `oms.events.processed{topic,type}`, `oms.events.duplicates`, `oms.events.retries`, `oms.events.dead_lettered`, `oms.events.ignored`.
* `GET /api/v1/ops/messaging` (OPERATOR/ADMIN) is auto-registered; shape = `MessagingStatus`. Lag via Kafka `AdminClient`; on any error `lagAvailable=false` and null lag.
  Config: `oms.messaging.topics`, `consumer-group`, `concurrency`, `admin-timeout-ms`, `ops-endpoint-enabled`, `enabled`.

## 6. Audit (`com.enterprise.oms.common.audit`)
`auditService.record("Order", orderId, "CANCELLED", Map.of("reason", r))` (joins the caller's tx; actor = JWT subject or `system`; correlationId from MDC; detail String or any object -> JSON, max 8000 chars).
`search(entityType, entityId, pageable)` -> `PageResponse<AuditEventView>`. `GET /api/v1/ops/audit?entityType=&entityId=&page&size&sort=` auto-registered (`oms.audit.ops-endpoint-enabled`, `oms.audit.enabled`).

## 7. Web
* `CorrelationIdFilter` (header `X-Correlation-Id`, validated, echoed, MDC `correlationId`), `RequestSizeLimitFilter` (`oms.web.max-body-bytes`=65536 -> 413 `PAYLOAD_TOO_LARGE`; also counts chunked bodies).
* `GlobalExceptionHandler` (problem+json, contract section 5; extends `ResponseEntityExceptionHandler`, so 400/404/405/415 are enriched too). Throw: `NotFoundException(resource,id)`, `ConflictException`,
  `InvalidStateTransitionException(entity, from, to)`, `InsufficientStockException(shortages)`, `ForbiddenException`, `RequestValidationException(field,msg)`, `IdempotencyKeyRequiredException`, `IdempotencyKeyReusedException`,
  `RateLimitedException`, `UpstreamUnavailableException`, or `new OmsException(ErrorCode.X, msg, fieldErrors)`. `ProblemFactory.create(...)` / `ProblemWriter` for manual use. Unexpected errors -> 500 `INTERNAL_ERROR`, no details.
* `PageResponse<T>` (`PageResponse.from(page)` / `from(page, mapper)`), `PageRequestFactory` bean:
  `pages.create(page, size, sortParams, Set.of("createdAt","status"), Sort.by(DESC,"createdAt"))` (size 1..100, whitelist, invalid -> 400).
* `IdempotencyKeys.require(headerValue)` (400 if absent/invalid), `IdempotencyKeys.fingerprint(body)` (SHA-256 hex) for key-reuse detection.
* Toggles/limits: `oms.web.enabled`, `max-body-bytes`, `max-page-size`, `default-page-size`.

## 8. Security (`com.enterprise.oms.common.security`, `JwtSecurityAutoConfiguration`)
* Resource server, stateless, HS256 (pinned) via Nimbus; validates signature, `exp`/`nbf` (skew 30s), `iss` (`oms-dev-issuer`), `aud` (`oms-api`). Secret: `oms.security.jwt.secret` or env `OMS_JWT_SECRET`;
  **startup fails** if absent or < 32 bytes, unless `oms.security.jwt.allow-ephemeral-secret=true` (random per JVM: use ONLY in tests or a single-process local run; services will not trust each other's tokens).
  OIDC mode: `oms.security.jwt.issuer-uri` and/or `jwk-set-uri` (RS256, JWKS); the HS256 secret/token beans are then absent.
* `roles` claim -> `ROLE_*` (only CUSTOMER, OPERATOR, ADMIN, SERVICE). Method security enabled: use `@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")`.
* Chain: permitAll `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/v3/api-docs/**`, swagger-ui; `/api/v1/internal/**` requires `SERVICE`; everything else authenticated. Add rules with a
  `OmsAuthorizeRequestsCustomizer` bean; replace everything by declaring your own `SecurityFilterChain`.
* CSRF disabled deliberately: stateless bearer-token API, no cookies/sessions, no ambient credentials a cross-site request could ride on. Headers: nosniff, frame DENY, no-referrer, HSTS. 401/403 are problem+json
  (`WWW-Authenticate: Bearer`, details never say why a token was rejected).
* `CurrentUser.require()` / `find()` -> `id`, `roles`, `customerId`; helpers `isStaff()`, `isCustomer()`, `canAccessCustomer(ownerCustomerId)` (return 404, not 403, for other customers' resources).
* `JwtTokenFactory.issue(sub, roles, customerId, ttl)` (gateway dev-token endpoint, tests; negative ttl = expired token), `ServiceTokenProvider.token()` (SERVICE token, `sub=svc-<application name>`, TTL 2 min, cached),
  `JwtSecret` (resolution + validation).

## 9. Outbound HTTP and resilience
`OmsRestClientFactory`: `create(baseUrl)` / `createServiceClient(baseUrl)` -> `RestClient` with connect 2s / read 3s (`oms.http.connect-timeout|read-timeout`), `X-Correlation-Id` forwarded from MDC,
and (service client) `Authorization: Bearer <service token>`. `resilience4j-spring-boot3` is on the classpath: configure `resilience4j.circuitbreaker.instances.*` / `retry.instances.*` and annotate with `@CircuitBreaker`/`@Retry`.

## 10. Observability
* Logging: shared `logback-spring.xml`; default plain console; profile `json-logs` -> JSON lines (`timestamp, level, service, correlationId, traceId, spanId, logger, message, stack_trace`). A service may ship its own `logback-spring.xml`.
* `LogMasking.maskEmail` (`a***@domain`), `maskToken`, `sanitize` (emails, JWTs, bearer, `password=`), `stripNewlines`. Never log tokens/emails in full.
* `Metrics` helpers (`counter`, `timer`, `gauge(registry, name, ownerBean, fn)`; Micrometer holds gauge owners weakly, pass a bean). Common tag `service`.
* Tracing: `micrometer-tracing-bridge-otel` + OTLP exporter are present but **off** by default (`OMS_TRACING_ENABLED=true`, `OMS_OTLP_ENDPOINT`, `OMS_TRACING_SAMPLING`); Kafka template/listener observation propagates `traceparent`.

## 11. Property reference
`oms.web.*`, `oms.http.*`, `oms.kafka.*`, `oms.outbox.*`, `oms.messaging.*` (incl. `retry.max-attempts|initial-interval-ms|multiplier|max-interval-ms`), `oms.audit.*`,
`oms.security.{enabled,jwt.{secret,allow-ephemeral-secret,issuer,audience,roles-claim,issuer-uri,jwk-set-uri,clock-skew,service-token-ttl}}`.

## 12. Verified vs not
Verified by `mvn verify` (H2 PostgreSQL mode + embedded KRaft Kafka): everything above incl. outbox publish/Kafka-down retry, idempotency, DLT flows, problem shapes, JWT validation matrix, fail-fast.
NOT verified: PostgreSQL itself (Testcontainers tests are skipped without Docker), OIDC/JWKS mode, OTLP export, `json-logs` profile output in a container, multi-instance relay contention.
