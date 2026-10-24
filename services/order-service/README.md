# order-service

Customers, orders and the order saga of the OMS. Port **18081**. Java 17, Spring Boot 3.5.16, JPA/Hibernate, Flyway,
Kafka (via `platform-common`), PostgreSQL 16 in production, H2 (PostgreSQL mode) locally and in tests.
Normative contract: `docs/architecture/00-shared-contract.md` (sections 5-8, and 13 for what this service clarifies).
Foundation (outbox, idempotent consumers, security, error handling, audit): `services/platform-common/README.md`.
All data is synthetic.

## 1. Responsibilities

* Owns **customers** and **orders** (state, history, price snapshot) and the **saga** that drives an order through
  validation, stock reservation, confirmation and fulfillment, compensating when something fails.
* Is the **orchestrator**: it tells inventory and fulfillment what to do with commands and learns the outcome from their
  events. Inventory owns stock and reservations, fulfillment owns shipments; order-service never reads their data.
* Publishes the order lifecycle events other services (notification, inventory) consume.
* Offers the operator tooling for the saga: failed-order list, step-level retry, saga inspection, reconciliation.

It does NOT own: products and stock (inventory-service), shipments (fulfillment-service), notifications, authentication
(the gateway issues dev tokens; this service only validates JWTs).

## 2. Run it

```
# jar built by `mvn -f services/pom.xml -pl order-service package` -> services/order-service/target/order-service-1.0.0.jar
OMS_JWT_SECRET=<at least 32 random bytes, never committed> \
JAVA_HOME=.../graalvm-jdk-17.0.12 java -jar services/order-service/target/order-service-1.0.0.jar
```

* Default profile is `dev`: in-memory H2 (PostgreSQL mode) + Flyway seed (3 customers, contract section 9), Kafka at
  `localhost:19092` (embedded broker of `local-stack`), inventory at `http://localhost:18082`.
* Without a reachable Kafka the service still starts (listeners and the outbox relay retry quietly); orders are accepted
  and their events wait in the outbox table until Kafka is back.
* Production: `SPRING_PROFILES_ACTIVE=prod` (optionally `prod,json-logs`) plus the environment in section 11. There are no defaults for the database or the JWT secret in `prod`: the service refuses to start without them.
* OpenAPI: `/v3/api-docs` (always), Swagger UI `/swagger-ui.html` (not in `prod`). Health: `/actuator/health/{liveness,readiness}`
  (readiness includes the database). Metrics: `/actuator/prometheus`.

## 3. API (via the gateway: `/api/v1/...`)

Errors are RFC 7807 `application/problem+json` with `code`, `correlationId`, `timestamp`, `errors[]` (contract section 5).
Role rules are enforced at URL level **before** the body is read (403 before 400); resource rules return **404** for
another customer's data.

| Method and path | Roles | Notes |
|---|---|---|
| `POST /customers` | OPERATOR, ADMIN | `{name, email, tier?}` -> 201 + Location; email unique (409), stored lower-case |
| `GET /customers/{id}` | self (CUSTOMER), OPERATOR, ADMIN | other customers: 404 |
| `GET /customers?q=&page&size&sort` | OPERATOR, ADMIN | `q` = prefix of name/email; sort `name,email,createdAt` |
| `POST /orders` | CUSTOMER (own customerId), OPERATOR, ADMIN | header `Idempotency-Key` required. **202** + `Location`, body = Order in `CREATED`. 400 `IDEMPOTENCY_KEY_REQUIRED` / `VALIDATION_FAILED`, 403 foreign customerId, 404 unknown customer, 422 `IDEMPOTENCY_KEY_REUSED`, 429 `RATE_LIMITED` (+`Retry-After`) |
| `GET /orders?status&customerId&from&to&q&page&size&sort` | CUSTOMER (own only, forced filter), OPERATOR, ADMIN | page envelope `{content,page,size,totalElements,totalPages}`; size 1..100 (400 outside, never clamped); sort whitelist `createdAt,updatedAt,status,totalAmount` |
| `GET /orders/{id}` | owner, OPERATOR, ADMIN | |
| `GET /orders/{id}/history` | owner, OPERATOR, ADMIN | `[{fromStatus,toStatus,reason,actor,occurredAt}]` |
| `POST /orders/{id}/cancel` | owner, OPERATOR, ADMIN | body optional `{reason}`; allowed from CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED, else 409 `INVALID_STATE_TRANSITION` |
| `GET /admin/orders/failed` | OPERATOR, ADMIN | REJECTED, FAILED, COMPENSATION_PENDING with no progress for `stuck-after-seconds` |
| `POST /admin/orders/{id}/retry` | OPERATOR, ADMIN | re-sends the current step (idempotent), resets attempts; terminal order -> 409 |
| `GET /admin/saga/{orderId}` | OPERATOR, ADMIN | saga + step log |
| `POST /admin/reconciliation/run` | ADMIN | `{examined, repaired, details[]}` |
| `GET /ops/messaging`, `GET /ops/audit` | OPERATOR, ADMIN | provided by platform-common (order audit uses `entityType=Order`, also `Customer`, `Reconciliation`) |

Order JSON: `{id, customerId, status, statusReason, totalAmount, currency, items[{productId, sku, name, quantity, unitPrice, lineTotal}], shippingAddress, correlationId, createdAt, updatedAt, version}`.
Until validation has priced the order `totalAmount` is `0.00` and sku/name/prices are `null`.

## 4. Data ownership (database `order_db`, own schema, no cross-service DB access)

| Table | Purpose |
|---|---|
| `customers` | id, name, unique email, tier |
| `orders` | aggregate root: status, reason, total, currency, shipping address, customer email snapshot, `version` (optimistic lock) |
| `order_items` | lines + price snapshot (`sku`, `name`, `unit_price`, `line_total`), unique (order, product) |
| `order_status_history` | one row per transition: from, to, reason, actor, time |
| `saga_instances` | one per order (unique `order_id`): state, current step, attempts, last error, compensation reason, known `reservation_id`, `reservation_released`, `fulfillment_started`, `version` |
| `saga_step_log` | append-only: step, status (STARTED/SUCCEEDED/FAILED/RETRIED/COMPENSATED/SKIPPED), attempt, detail |
| `idempotency_keys` | unique (`scope`=customerId, `idem_key`): request fingerprint + response snapshot |
| `outbox_events`, `processed_events`, `audit_events` | from platform-common (migration V1) |

Migrations (portable SQL for PostgreSQL and H2): `V1` platform-common, `V10__customers_orders`, `V11__saga_idempotency`,
`V12__indexes`, seed `V1000__seed_customers` (location `db/seed`, only in profiles `dev` and `local`).

**Index design** (`V12`): every list is "newest first", so each index ends in `created_at`:
`(customer_id, created_at)` for the customer's own list, `(customer_id, status, created_at)` for customer+status,
`(status, created_at)` for operator status filters, `(created_at)` for the default list and date ranges,
`(updated_at)` for the stuck-compensation test; `saga_instances(state, updated_at)` for the recovery job,
`(reservation_released, state)` for reconciliation; unique indexes back idempotency and one-saga-per-order.
The search avoids N+1 and in-memory paging: page + count run on `orders` alone, then ONE fetch-join query loads the
items of exactly that page's ids (3 statements for any page size; asserted in `OrderRepositoryTest`). `q` is an order id
or a case-insensitive PREFIX of customer email / city; on PostgreSQL add `lower(customer_email) text_pattern_ops` /
`pg_trgm` indexes if free text becomes hot (not portable to H2, so not in the migrations).

## 5. Messaging

| Direction | Topic | Types |
|---|---|---|
| produce | `oms.order.events.v1` | OrderCreated, OrderConfirmed, OrderCancelled, OrderRejected, OrderFulfilled, OrderFailed |
| produce | `oms.inventory.commands.v1` | ReserveInventory, ReleaseInventory |
| produce | `oms.fulfillment.commands.v1` | StartFulfillment |
| consume | `oms.inventory.events.v1` | InventoryReserved, InventoryReservationFailed, InventoryReleased |
| consume | `oms.fulfillment.events.v1` | FulfillmentStarted, FulfillmentCompleted, FulfillmentFailed |

Key = orderId. **Producing**: every state change writes its events to the `outbox_events` table in the SAME transaction
(`OutboxService.append` refuses to run without one); the relay publishes with `acks=all`, idempotence on, marks
`published_at`: at-least-once. **Consuming**: `EventRouter` inserts `processed_events(consumer, event_id)` in the SAME
transaction as the effect, so a redelivery is skipped (`oms.events.duplicates`) and a failure rolls back both; retry with
backoff (4 attempts), then `<topic>.DLT` with `x-exception-*` headers; malformed/unconvertible events go straight to DLT;
unknown event types are ignored and counted. Consumer group `order-service`. On top of that the state machine makes every
handler idempotent in its own right (a replay under a NEW eventId is a no-op).

`OrderCreated` is published when validation has priced the order (it carries the price snapshot), together with
`ReserveInventory`; see contract section 13.

## 6. The saga

```
 POST /orders -> 202            VALIDATE (sync REST, no DB tx)          RESERVE_INVENTORY            CONFIRM_ORDER        FULFILL
 CREATED  ------------------>  CREATED --ok--> VALIDATED --ReserveInventory--> (InventoryReserved) --> INVENTORY_RESERVED --> CONFIRMED --StartFulfillment--> FULFILLED
    |  any line not ok                |                       | InventoryReservationFailed                                       | FulfillmentFailed
    v                                 v                       v                                                                   v
 REJECTED                       REJECTED                  REJECTED            COMPENSATION_PENDING --ReleaseInventory--> (InventoryReleased) --> CANCELLED (cancel) | FAILED (fulfillment failure)
 cancel: CREATED/VALIDATED -> CANCELLED at once; INVENTORY_RESERVED/CONFIRMED -> COMPENSATION_PENDING -> CANCELLED
 InventoryReleased(EXPIRED) while VALIDATED/INVENTORY_RESERVED -> FAILED;  retries exhausted, nothing to undo -> FAILED
```

* `OrderStateMachine` (pure domain class, `(status, trigger) -> TRANSITION | NO_OP | INVALID`) holds ALL transition
  rules; `OrderTransitions` is the only code that changes `Order.status` and writes history + audit (+ the processing
  timer on terminal states). `OrderSagaOrchestrator` decides what to send.
* **Validation** (`RestInventoryClient`): `OmsRestClientFactory.createServiceClient(oms.inventory.base-url)` (SERVICE JWT,
  correlation id, connect 2s / read 3s) + Resilience4j: retry 3 x with exponential backoff for transient failures only, inside
  a circuit breaker (opens at 50% of 10 calls, 10s). The call runs outside any DB transaction. Transient failure keeps the
  order `CREATED`, counts an attempt, and the recovery job tries again; after `max-attempts` the order is `FAILED`.
* **Late reservation after cancel**: `InventoryReserved` for a `CANCELLED` order sends `ReleaseInventory(CANCELLATION)`;
  the order stays cancelled. **Expiry**: `InventoryReleased(EXPIRED)` fails an order still waiting to be confirmed.
* **Duplicates / out of order**: duplicates by eventId are skipped; a stale event (e.g. `FulfillmentFailed` after
  `FULFILLED`) is ignored, audited as `TRANSITION_IGNORED` and counted (`oms.saga.events.ignored`), never dead-lettered;
  events for unknown orders are ignored and counted.
* **Optimistic locking**: `Order` and `SagaInstance` have `@Version`; API commands, recovery and reconciliation run in
  `RetryingTransactions` (re-read and re-decide on conflict, up to 5 times); a Kafka handler that loses simply fails and
  the container retries it.
* **SagaRecoveryJob** (`oms.saga.recovery-interval-ms`): re-drives RUNNING/COMPENSATING sagas with no progress for
  `oms.saga.stuck-after-seconds` by re-sending the current step's idempotent command (CREATED: re-runs validation).
  After `oms.saga.max-attempts`: CREATED/VALIDATED/INVENTORY_RESERVED -> `FAILED` (+ a defensive `ReleaseInventory`
  when a reservation might exist); CONFIRMED -> treated as a fulfillment failure (`COMPENSATION_PENDING`, release);
  COMPENSATION_PENDING -> saga `NEEDS_ATTENTION` (order stays, alert counter `oms.saga.alerts`), re-opened by
  `POST /admin/orders/{id}/retry` or reconciliation.
* **ReconciliationService** (scheduled + `POST /admin/reconciliation/run`, results audited as `RECONCILIATION_RUN` /
  `ORDER_RECONCILED`): repairs by re-issuing idempotent commands for (1) a terminal order whose known reservation was never
  confirmed released (covers "order terminal but reservation not released" and "CANCELLED order with a RESERVED
  reservation"), (2) a CONFIRMED order with no fulfillment progress, (3) a flagged compensation.
  **It cannot see** inventory's real reservation/stock state or fulfillment's shipment state: it only knows what those
  services told order-service (`reservation_id` from `InventoryReserved`, `InventoryReleased`, `FulfillmentStarted`) and
  repairs by asking again. An inconsistency that never produced any message here (inventory reserved but the reply was
  lost AND nothing else ever referred to it) is bounded by inventory's own TTL expiry (`EXPIRED` releases it), not detected here.
* NOT a distributed ACID transaction: between steps the services are eventually consistent and compensation is semantic.

## 7. Idempotency of `POST /orders`

One transaction: look up (customerId, key); found -> compare the SHA-256 fingerprint of the canonical request (customerId,
lines sorted by product, trimmed address) -> same: replay the stored response snapshot (`202` + `Idempotent-Replay: true`),
different: `422 IDEMPOTENCY_KEY_REUSED`. Not found: INSERT the key row FIRST (unique `(scope, idem_key)`) so a concurrent duplicate blocks on the
unique index instead of creating a second order, then create order + saga + history + audit, store the response snapshot,
commit. The loser gets a unique violation (or a lock timeout), re-reads the committed row and replays it. Two threads with the
same key and body create exactly one order (`IdempotencyApiTest`, `OrderBddScenariosTest`). Failed requests do not burn the key;
keys expire after `oms.idempotency.retention` (24h, hourly cleanup job).

## 8. Authorization and security boundaries

* JWT (HS256 in demo, OIDC-switchable) validated by platform-common: signature, exp, iss, aud; stateless, no CSRF surface.
* URL rules (`OrderSecurityConfig`) + `@PreAuthorize` (defense in depth) + resource rules in the services.
  CUSTOMER: own orders/customer only (others 404), `customerId` of a created order must equal the token's (403),
  list filter forced to self. OPERATOR: all reads, cancel any order, retry, create customers. ADMIN: everything an
  OPERATOR can do, plus running reconciliation (ADMIN only). SERVICE tokens are refused on customer-facing endpoints.
* Boundaries: this service trusts only the validated JWT (never headers such as `X-User-Role`; mass-assignment of
  `status/version/prices` in a body is ignored), calls inventory only with a short-lived SERVICE token, exposes no
  `/internal/**` endpoint, logs no emails/tokens in full (masking helpers), returns no stack traces, caps JSON bodies at 64 KB.
  Test token helpers generate a random secret per JVM: there is no secret anywhere in the repository.
* Rate limit: `POST /orders` per caller (JWT subject), token bucket (`capacity`, `refill-per-second`), 429 + `Retry-After`.
  In-memory by default (per instance: N instances allow N x capacity); `oms.redis.enabled=true` switches to a Redis fixed-window
  counter shared by all instances and falls back to in-memory if Redis is unreachable (fail-open per instance).

## 9. Observability

Metrics (Micrometer, tag `service=order-service`): `oms.order.processing.duration{outcome}` (timer, CREATED -> terminal,
percentile histogram), `oms.saga.active` (gauge), `oms.saga.compensations{reason}`, `oms.saga.alerts{kind}`,
`oms.saga.recovered{result}`, `oms.saga.events.ignored{type}`, plus platform `oms.events.{processed,duplicates,retries,dead_lettered,ignored}`,
`oms.outbox.pending`, and `http.server.requests` with histogram buckets. Logs: plain console locally, JSON with profile `json-logs`
(`correlationId`, `traceId`, `spanId`); correlation id from `X-Correlation-Id` is copied to every event and outbound call.
Audit: every status change (`STATUS_<TO>`), order create/cancel, customer create, saga re-drive/exhaustion, late-reservation
release, reconciliation, ignored transitions; query with `/api/v1/ops/audit?entityType=Order&entityId=<id>`.

## 10. Profiles

`dev` (default; seed + debug logs + leak detection), `local` (seed, faster saga recovery, health details), `prod`
(PostgreSQL from env, no seed, Swagger UI off, bigger pool, no secrets in the file), `json-logs` (combine: `prod,json-logs`).

## 11. Configuration reference

| Property | Env | Default |
|---|---|---|
| `oms.security.jwt.secret` | `OMS_JWT_SECRET` | none, >= 32 bytes, startup fails without |
| `spring.datasource.url / username / password` | `OMS_DB_URL`, `OMS_DB_USER`, `OMS_DB_PASSWORD` | H2 in `dev`; **required** in `prod` |
| `spring.datasource.hikari.maximum-pool-size` / `minimum-idle` | `OMS_DB_POOL_MAX` / `OMS_DB_POOL_MIN_IDLE` | 10 / 2 (prod 20 / 5) |
| `spring.kafka.bootstrap-servers` | `OMS_KAFKA_BOOTSTRAP` | `localhost:19092` |
| `oms.inventory.base-url` | `OMS_INVENTORY_BASE_URL` | `http://localhost:18082` |
| `oms.saga.stuck-after-seconds` | `OMS_SAGA_STUCK_AFTER_SECONDS` | 60 (local 30) |
| `oms.saga.max-attempts` | `OMS_SAGA_MAX_ATTEMPTS` | 5 |
| `oms.saga.recovery-enabled` / `recovery-interval-ms` | `OMS_SAGA_RECOVERY_ENABLED` / `OMS_SAGA_RECOVERY_INTERVAL_MS` | true / 10000 |
| `oms.saga.reservation-ttl-seconds` | `OMS_SAGA_RESERVATION_TTL_SECONDS` | 900 |
| `oms.saga.auto-start`, `oms.saga.listeners-enabled` | | true, true (test switches) |
| `oms.reconciliation.enabled` / `interval-ms` / `min-age-seconds` | `OMS_RECONCILIATION_ENABLED` / `_INTERVAL_MS` / `_MIN_AGE_SECONDS` | true / 300000 / 120 |
| `oms.idempotency.retention` | `OMS_IDEMPOTENCY_RETENTION` | PT24H |
| `oms.order.rate-limit.enabled / capacity / refill-per-second` | `OMS_ORDER_RATE_LIMIT_ENABLED` / `OMS_ORDER_RATE_CAPACITY` / `OMS_ORDER_RATE_REFILL_PER_SECOND` | true / 20 / 5 |
| `oms.redis.enabled` (+ `spring.data.redis.*`) | `OMS_REDIS_ENABLED`, `OMS_REDIS_HOST`, `OMS_REDIS_PORT`, `OMS_REDIS_PASSWORD` | false (in-memory limiter) |
| `oms.messaging.concurrency` | `OMS_KAFKA_CONCURRENCY` | 1 (prod 3 = partitions) |
| `server.port`, `server.tomcat.threads.max` | `SERVER_PORT`, `OMS_TOMCAT_MAX_THREADS` | 18081, 100 |
| tracing (platform-common) | `OMS_TRACING_ENABLED`, `OMS_TRACING_SAMPLING`, `OMS_OTLP_ENDPOINT` | off |
| Resilience4j | `resilience4j.retry.instances.inventory.*`, `resilience4j.circuitbreaker.instances.inventory.*` | see `application.yml` |

**Hikari sizing**: a request holds a connection only for the duration of its transaction (no open-in-view, validation REST
call outside any transaction), so the pool is sized for concurrent transactions, not HTTP threads. Connection timeout 3s
(fail fast), max lifetime 25 min (below typical 30 min server/proxy limits), leak detection on in dev/prod.
**Graceful shutdown**: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`; the saga executor waits
for running validations (20s); anything unfinished is picked up by the recovery job after restart.

## 12. Failure modes

| Failure | What happens | Recovery |
|---|---|---|
| Kafka down | API keeps accepting orders; events accumulate in `outbox_events` (`oms.outbox.pending` grows); consumers retry | relay publishes the backlog in order when Kafka returns (`OrderResilienceKafkaIT`) |
| Relay crashes after publish, before marking | event re-sent | consumers de-duplicate by eventId |
| Service crashes mid-handler | whole transaction (state + outbox + processed marker) rolled back | Kafka redelivers; or recovery job |
| Inventory down at validation | order stays `CREATED`, attempts counted, circuit opens | recovery job re-validates; `FAILED` after max attempts |
| Inventory never answers a reserve | order stays `VALIDATED` | recovery job re-sends the idempotent command; `FAILED` + defensive release after max attempts |
| Reply lost / delivered twice / out of order | skipped, ignored or no-op by the state machine | none needed |
| Cancel racing a reservation | optimistic lock or late-reservation compensation | automatic; order stays `CANCELLED`, reservation released |
| Fulfillment silent | CONFIRMED order re-sends `StartFulfillment`; after max attempts compensates | reconciliation / admin retry |
| Compensation cannot complete | saga `NEEDS_ATTENTION`, alert metric, order stays `COMPENSATION_PENDING` | admin retry or reconciliation |
| Poison message | DLT after non-retryable classification / 4 attempts, partition keeps moving | operators inspect `<topic>.DLT` |
| Redis down (when enabled) | limiter falls back to in-memory | automatic |
| DB down | readiness DOWN, 5xx (`INTERNAL_ERROR`, no details), listeners retry | automatic when DB returns |

## 13. Scaling

Stateless; run N instances behind the gateway. Kafka partitions (3) bound consumer parallelism per group; per-order ordering holds
because the key is orderId. The outbox relay and the scheduled jobs run on every instance: safe (idempotent commands,
`@Version`, de-duplicating consumers) but they can duplicate work; before running many replicas, give the relay row-claiming
(`FOR UPDATE SKIP LOCKED`) and put the recovery/reconciliation jobs behind a lock (ShedLock) or enable them on one instance. The
in-memory rate limit is per instance (use Redis for a shared one). Read paths are index-backed and bounded (page size <= 100).
Archive/partition `order_status_history`, `saga_step_log` and `audit_events` by time when volumes grow.

## 14. Tests and verification status

`mvn -f services/pom.xml -pl order-service verify` (inline JAVA_HOME, no `-am`; the parent pom must be in `~/.m2`, see contract 13).

* **Unit and H2 (surefire): 433 tests, 0 failures**: state machine (207, every status pair and every status x trigger),
  orchestrator and services with Mockito, rate limiters (incl. Redis limiter against a mocked template), Bean Validation and
  fingerprint, MockMvc REST (validation, error shape, pagination, authorization matrix, expired/forged/tampered/alg=none tokens,
  privilege escalation attempts), idempotency (replay, mismatch, scope, 2 and 8 concurrent threads), repository/constraints/N+1
  (statement count), saga on a real schema (duplicates, replays, out of order, expiry, late reservation, optimistic-lock retry),
  recovery, reconciliation, the REAL REST inventory client against a fake HTTP server (retry, timeout, circuit breaker, service
  token), metrics/OpenAPI/health/config, seed migration, BDD scenarios (`OrderBddScenariosTest`, mirrored 1:1 in
  `tests/bdd/order-scenarios.feature`).
* **Kafka (failsafe, embedded KRaft, `*IT`): 20 tests, 0 failures**: full saga happy path with simulated inventory/fulfillment
  replies, insufficient stock, cancellation with compensation, fulfillment failure -> COMPENSATION_PENDING -> FAILED, duplicate
  event, replay under a new id, out-of-order, poison -> DLT, outbox crash/relay delay, recovery job, reconciliation.
* **PostgreSQL (Testcontainers) `OrderSearchPostgresIT`: 7 tests, SKIPPED here** (no Docker): migrations on PostgreSQL 16 and
  `EXPLAIN` of the search queries against the V12 indexes. Written and compiled, **never executed**.
* Run once, by hand: the packaged jar booted (dev profile, seed applied, Tomcat up) with a random secret and no Kafka.

**Not verified**: PostgreSQL itself, the Redis limiter against a real Redis, `json-logs` output, the `prod` profile against a real
database, OIDC/JWKS mode, behaviour with several instances (relay and job contention), real cross-service flow with the actual
inventory/fulfillment/notification services (other work-streams; this service was tested against stand-ins that follow the contract).

## 15. Known limitations

* A cancel that arrives while fulfillment is already finishing moves the order to `COMPENSATION_PENDING`; a later
  `FulfillmentCompleted` is ignored by the state machine (the order is not resurrected), so inventory may still ship stock
  that this service has released: a cross-service race that needs a fulfillment-side cancel to close.
* `FAILED`/`REJECTED` orders cannot be retried (terminal); the customer places a new order.
* `q` is a prefix match, not "contains".
* Idempotency scope is the body's customerId: two staff members using one key for one customer share it (intended).
