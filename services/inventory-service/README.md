# inventory-service

Product catalog, stock and reservations of the OMS (contract: `docs/architecture/00-shared-contract.md`, sections 3, 5.2, 7, 9
and **14** for what this service clarifies). Spring Boot 3.5.16, Java 17, JPA/Hibernate, Flyway, Kafka (via
`platform-common`), springdoc. Port **18082**. All data is synthetic.

## 1. Responsibilities

* Owns the **product catalog** (CRUD for ADMIN, search for everybody) and **stock** (`on_hand`, `reserved`, optimistic
  `version`) of every product.
* Executes **reservations** for the order saga: all-or-nothing, exactly one per order, TTL-bound, with three selectable
  concurrency strategies (section 5).
* Reacts to the order lifecycle: confirm (commit), cancel/release, fulfilment completed (ship), expiry (scheduler).
* Publishes `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased` through the **transactional outbox**.
* Does NOT own orders, customers, fulfilment or notifications and never reads their databases.

## 2. APIs (`/v3/api-docs`, `/swagger-ui.html`, committed export `specs/openapi/inventory-service.json`)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/v1/products?q&category&inStock&page&size&sort` | CUSTOMER, OPERATOR, ADMIN | customers: active products, `available` only; staff: all + `onHand`, `reserved`, `version`. `sort` in `sku,name,category,price,createdAt,available` |
| `GET /api/v1/products/{id}` | CUSTOMER, OPERATOR, ADMIN | read-through cache (section 7); inactive = 404 for customers |
| `POST /api/v1/products` | ADMIN | `{sku,name,description?,category,price,currency?=USD,initialStock?}` -> 201 + `Location`; duplicate SKU 409 |
| `PUT /api/v1/products/{id}` | ADMIN | `{name,description,category,price,active?,version?}`; stale `version` 409; SKU immutable |
| `DELETE /api/v1/products/{id}` | ADMIN | deactivate (idempotent), 200 + product |
| `GET /api/v1/inventory?sku&lowStock&page&size&sort` | OPERATOR, ADMIN | `lowStock` = `available <= oms.inventory.low-stock-threshold` (10) |
| `GET /api/v1/inventory/{productId}` | OPERATOR, ADMIN | |
| `PUT /api/v1/inventory/{productId}/stock` `{delta, reason}` | OPERATOR, ADMIN | optimistic `@Version` + bounded retry; result < `reserved` = 409 `CONFLICT`; writes `stock_movements` + audit; returns Product JSON |
| `GET /api/v1/inventory/{productId}/movements` | OPERATOR, ADMIN | ledger, newest first (additive endpoint) |
| `GET /api/v1/inventory/reservations?orderId&status&page&size` | OPERATOR, ADMIN | items with SKUs; `status` one of RESERVED, COMMITTED, RELEASED, EXPIRED, FAILED |
| `POST /api/v1/internal/products/validate` | SERVICE | `{items:[{productId,quantity}]}` -> `{valid, lines[]}` (reads the DB, never the cache; advisory) |
| `GET /api/v1/ops/messaging`, `GET /api/v1/ops/audit` | OPERATOR, ADMIN | provided by platform-common |
| `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` | open | as in the contract |

Errors are RFC 7807 `application/problem+json` (platform-common). `INSUFFICIENT_STOCK` is not an HTTP result here:
shortages are reported by the reservation events and the validate lines. Paging errors are 400, never clamped.

## 3. Data ownership and tables (Flyway `V10__inventory_schema.sql`; seed `V1000__seed_products.sql` in profile `dev`)

| Table | Content |
|---|---|
| `products` | catalog row (`sku` unique, `price` NUMERIC(12,2), `active`, `version`) |
| `stock` | one row per product: `on_hand`, `reserved`, `version`; CHECKs `on_hand >= 0`, `reserved >= 0`, `reserved <= on_hand` |
| `inventory_reservations` | ONE row per order (`order_id` unique) in any state: RESERVED, COMMITTED, RELEASED, EXPIRED, FAILED; `expires_at`, `release_reason`, `failure_reason`/`failure_detail`, `shipped_at` |
| `inventory_reservation_items` | lines of a reservation (also kept for FAILED ones) |
| `stock_movements` | append-only ledger: ADJUSTMENT, RESERVE, RELEASE, EXPIRE, SHIP |
| `outbox_events`, `processed_events`, `audit_events` | platform-common tables (`V1`) |

Indexes and plans: `docs/database/inventory-explain-analyze.md`.

## 4. Kafka

Consumer group = `inventory-service`; handlers run through `EventRouter` + `IdempotentEventProcessor` (the
`processed_events` marker and the business effect commit in ONE transaction).

| Topic | Consumes | Effect |
|---|---|---|
| `oms.inventory.commands.v1` | `ReserveInventory` | reserve (section 5); duplicate order = original outcome re-emitted |
| | `ReleaseInventory{reason}` | release RESERVED/COMMITTED, answer `InventoryReleased` (always) |
| `oms.order.events.v1` | `OrderConfirmed` | RESERVED -> COMMITTED, TTL cleared |
| | `OrderCancelled` | release (reason CANCELLATION); other order events are ignored (`oms.events.ignored`) |
| `oms.fulfillment.events.v1` | `FulfillmentCompleted` | ship once: `on_hand -= q; reserved -= q`; other events ignored |
| `oms.inventory.events.v1` (produced, key = orderId) | | `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased` via outbox relay |

Poison messages: malformed JSON, `IllegalArgumentException` (empty items, quantity < 1, missing orderId, unknown release
reason) go straight to `<topic>.DLT` without retries and do not block the partition; infrastructure errors are retried
(500 ms x2, 4 attempts) and then dead-lettered (platform-common).

## 5. Reservation core and the three strategies

Inside ONE transaction (the Kafka processed-event transaction for commands):

1. normalize: merge duplicate lines, validate, **sort by productId** (global lock order, rules out deadlocks);
2. **claim the order's slot** (`ReservationClaims`: INSERT in a JDBC savepoint; a duplicate gets the ORIGINAL outcome);
3. catalog check (unknown / inactive product = FAILED with reason, no stock touched);
4. `ReservationStrategy.tryReserve` (below) - all-or-nothing, full shortage list on failure;
5. items, movements, audit, outbox event, cache eviction after commit.

| `oms.inventory.reservation-strategy` | Mechanism | Trade-off |
|---|---|---|
| `atomic` (**default**) | `UPDATE stock SET reserved = reserved + :q, version = version + 1 WHERE product_id = :id AND on_hand - reserved >= :q`; 0 rows = insufficient | no read, no app lock, no retry; compensates earlier lines on a shortage |
| `pessimistic` | `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`) every line in order, check, write | simple; nothing to compensate; rows locked even for a "no"; queues on hot rows |
| `optimistic` | read `(on_hand, reserved, version)`, compare-and-set `... WHERE version = :seen`, bounded retry (`optimistic-max-retries`, 20) | cheapest when contention is low; retry storms on hot rows; `ReservationContentionException` when the bound is hit |

Same behaviour contract for all three: the same concurrency suite (50 threads x 1 unit vs stock 10 = exactly 10 reserved,
opposite item orders, concurrent duplicate commands, contended multi-product ledger) passes for each, on H2 and on a real
PostgreSQL 16.4.

Benchmark (whole `reserve()` transaction, 16 threads x 150 calls per cell, laptop, `-Doms.bench=true`; reproduce with
`-Dtest=StrategyBenchmarkTest` or `-Dit.test=InventoryLocalPostgresIT#benchmarkStrategiesOnPostgres`):

| PostgreSQL 16.4 | atomic | pessimistic | optimistic |
|---|---|---|---|
| hot row, 1 unit/order (ops/s, p50 ms) | 129, 45.5 | 164, 40.9 | 64, 233.4 (2 389 conflicts) |
| 200 products spread (ops/s, p50 ms) | 798, 9.4 | 800, 8.2 | 827, 10.0 (72 conflicts) |
| 3-line orders over 10 hot products (ops/s, p50 ms) | 328, 18.5 | 289, 21.4 | 123, 50.7 (2 303 conflicts) |

Take-aways: one hot row serializes everything whatever you do (and every reservation also writes items, movement,
outbox and audit rows, so absolute throughput is modest on a laptop); optimistic only wins or ties when rows are spread;
atomic is the safe default (no read-modify-write window, nothing to tune). Full tables including H2 and the caveats are in
`docs/database/inventory-explain-analyze.md` section 4.

### Lifecycle after the reservation

`RESERVED` (counts in `stock.reserved`, TTL `oms.inventory.reservation-ttl-seconds`, default 900, capped at
`max-reservation-ttl-seconds`) -> `COMMITTED` (OrderConfirmed, TTL cleared) -> shipped (`shipped_at`, status stays
COMMITTED); `RELEASED` (ReleaseInventory / OrderCancelled); `EXPIRED` (scheduler: emits `InventoryReleased{reason:EXPIRED}`);
`FAILED` (refused). Every transition is a **status-guarded UPDATE**, so expiry vs confirm, duplicate commands and several
service instances can never both win. Release before any reservation writes a RELEASED **tombstone** so a late
ReserveInventory of a cancelled order can never take stock. A confirmation that arrives after expiry is rejected and
counted (`oms.inventory.reservation.late_commit`); it is left to reconciliation (order-service).

## 6. Configuration

| Property (env) | Default | Meaning |
|---|---|---|
| `server.port` | 18082 | |
| `spring.datasource.url/username/password` (`OMS_DB_URL`, `OMS_DB_USERNAME`, `OMS_DB_PASSWORD`) | in-memory H2 PostgreSQL mode, user `sa` | prod profile (`application-prod.yml`) has **no defaults**: startup fails without them; PostgreSQL driver |
| `OMS_JWT_SECRET` | none | **required**, >= 32 bytes (platform-common fails fast) |
| `OMS_KAFKA_BOOTSTRAP` | `localhost:19092` | |
| `oms.inventory.reservation-strategy` (`OMS_RESERVATION_STRATEGY`) | `atomic` | `atomic` / `pessimistic` / `optimistic` |
| `oms.inventory.reservation-ttl-seconds` (`OMS_RESERVATION_TTL_SECONDS`) | 900 | default TTL when the command carries none |
| `oms.inventory.expiry-scheduler-interval-ms` (`OMS_EXPIRY_INTERVAL_MS`), `expiry-scheduler-enabled`, `expiry-batch-size` | 30000, true, 100 | expiry scan |
| `oms.inventory.optimistic-max-retries`, `stock-adjust-max-retries` | 20, 5 | bounded retries |
| `oms.inventory.low-stock-threshold` | 10 | |
| `oms.inventory.listeners-enabled` | true | false in pure REST/DB tests |
| `oms.redis.enabled` (`OMS_REDIS_ENABLED`), `OMS_REDIS_HOST`, `OMS_REDIS_PORT`, `OMS_REDIS_PASSWORD` | false | product-read cache backend |
| `oms.inventory.cache.ttl-seconds`, `max-entries` | 30, 1000 | cache bounds |
| profile `dev` | | adds `classpath:db/seed` (20 products `SKU-1001..SKU-1020`, ids `a0000000-0000-0000-0000-0000000010NN`) |

Run locally (no Docker; embedded Kafka comes from `services/local-stack`):
`OMS_JWT_SECRET=<32+ random bytes> java -jar services/inventory-service/target/inventory-service-1.0.0.jar --spring.profiles.active=dev`.

### Hikari tuning (`spring.datasource.hikari.*`)

| Setting | Value | Why |
|---|---|---|
| `pool-name` | `inventory-hikari` | metric tag `pool` |
| `maximum-pool-size` (`OMS_DB_POOL_SIZE`) | 20 | each reservation holds a connection for one short transaction; the database saturates long before 20 does. Do not size to thread counts; `instances x size` must stay below PostgreSQL `max_connections` |
| `minimum-idle` | 5 | warm connections for bursts |
| `connection-timeout` (`OMS_DB_CONNECTION_TIMEOUT_MS`) | 3000 ms | fail fast instead of piling request threads; callers (saga, Kafka backoff) retry |
| `max-lifetime` / `idle-timeout` | 25 min / 5 min | below server/pooler idle limits |
| `leak-detection-threshold` (`OMS_DB_LEAK_DETECTION_MS`) | 0 (off) | 10000 while hunting leaks |

Investigation recipes: `docs/database/slow-query-lock-contention-pool-exhaustion.md`.

## 7. Product-read cache and its staleness trade-off

`ProductCache` (interface) has two implementations: a small bounded TTL map in memory (default, no library) and a Redis one
(`StringRedisTemplate`, JSON values with TTL, only when `oms.redis.enabled=true`; every Redis error degrades to a cache
miss). It caches **only `GET /products/{id}`** (full view incl. stock numbers).

* Evicted after every commit that changes the product or its stock (reserve, release, expire, ship, adjust, update,
  deactivate), never before commit.
* Staleness is bounded by `cache.ttl-seconds` (30 s) and by two windows: a reader that loaded the DB value before a commit and
  stores it after the eviction, and, with the in-memory implementation, a change made on ANOTHER instance (Redis is shared
  and has no such gap). A customer may therefore see a slightly old `available`; they never get a wrong reservation.
* **`available` from a cache is never used to decide anything**: reservations, the internal validate endpoint and the
  inventory/stock endpoints read the database; a test proves a stale cache cannot make a reservation succeed.

## 8. Failure modes

| Failure | Behaviour |
|---|---|
| duplicate / replayed command or event | `processed_events` skip; a new `eventId` for the same order returns the original outcome (re-emitted event); releases and ships are guarded no-ops |
| two reservations race for the last unit | one wins (row lock + re-evaluated predicate), the other gets FAILED with the shortage |
| crash between DB commit and Kafka publish | outbox row is already committed; the relay delivers it later (at-least-once; consumers dedupe) |
| Kafka down | the relay retries, REST keeps working; consumers resume from committed offsets |
| exception in a handler | transaction (claim, stock changes, marker) rolls back entirely; container retries, then DLT |
| optimistic contention exhausted | `ReservationContentionException` -> rollback -> container retry (switch to `atomic` for hot rows) |
| confirmation after expiry | rejected, `oms.inventory.reservation.late_commit`++, order-service reconciliation repairs |
| admin lowers stock below reserved | 409, nothing changes; concurrent stock edits retry on `@Version` |
| Redis down | cache degrades to misses |
| DB pool exhausted | requests fail fast after 3 s; see the investigation doc |

## 9. Scaling

Stateless: run N instances behind the gateway; the expiry job and the outbox relay are multi-instance safe (guarded
UPDATEs; duplicate publishes are tolerated by idempotent consumers). Kafka partitions (3, key = orderId) bound consumer
parallelism per topic (`oms.messaging.concurrency`). Real scaling limits: a single hot SKU row (queueing is correct; option
for later: split its stock into buckets) and the PostgreSQL connection budget. Catalog reads scale with the optional cache
and read replicas.

## 10. Security boundaries

JWT resource server (platform-common; HS256 secret from `OMS_JWT_SECRET`, never in code). Method security on every
controller (`@PreAuthorize`) on top of the URL rule that `/api/v1/internal/**` requires SERVICE (and is not routed by the
gateway). Customers never see stock internals or deactivated products; staff-only inventory endpoints return 403 to
customers and services. Input is bound by Bean Validation, sort fields are whitelisted, filters are bound parameters
(LIKE wildcards escaped), bodies are capped at 64 KB (413), JSON floats are not coerced to ints. Audit trail for product
changes, stock adjustments and reservation transitions (`/api/v1/ops/audit`). No secrets in code, config defaults or tests
(tests generate their HS256 secret at runtime; the prod profile has no credential defaults).

## 11. Tests (`mvn -B -f services/pom.xml -pl inventory-service verify`)

| Group | Classes | Executions |
|---|---|---|
| unit (Mockito, no Spring) | `ReservationStrategyUnitTest` 10, `ReservationServiceUnitTest` 6, `ProductCacheTest` 10 (unit + integration) | 26 |
| service / lifecycle on H2 | `ReservationServiceTest` 18, `ReservationLifecycleTest` 13 | 31 |
| concurrency (3 strategies) | `ReservationConcurrencyTest` | 15 |
| REST via MockMvc (real JWTs: authz matrix, validation, problem shapes, paging, filters) | `ProductApiTest` 16, `InventoryApiTest` 15, `ActuatorAndDocsTest` 4 | 35 |
| DB / migrations / seed | `InventoryDbTest` 9, `SeedDataTest` 4 | 13 |
| BDD mirror of `tests/bdd/inventory-scenarios.feature` | `InventoryBddScenariosTest` (11 `@Nested` scenarios) | 28 |
| Kafka on embedded KRaft (failsafe) | `InventoryKafkaIT` | 14 |
| PostgreSQL | `InventoryPostgresIT` (Testcontainers, skipped without Docker), `InventoryLocalPostgresIT` (`OMS_TEST_PG_URL`) | 16 scenarios each |
| opt-in benchmark | `StrategyBenchmarkTest` / `...#benchmarkStrategiesOnPostgres` (`-Doms.bench=true`) | |

TDD narrative: `docs/tdd/inventory-reservation-tdd.md`.

## 12. Verified vs not verified

* Verified by the build: everything in section 11 on H2 (PostgreSQL mode) and embedded KRaft Kafka; the packaged jar boots
  (see the build report).
* Verified on a real **PostgreSQL 16.4** (binaries from a Maven-cached `io.zonky` artifact, not Docker): the
  `PostgresScenarios` suite (all three strategies, 16 executions, run twice), the EXPLAIN ANALYZE captures, the lock-wait
  and `pg_stat_statements` captures, the benchmark.
* NOT verified: the Testcontainers variant itself (needs Docker, skipped), the Redis cache against a real Redis (only the
  Redis implementation with a mocked template and the in-memory path are tested), production-sized data, multi-instance
  runs (the guards are designed and unit/race-tested in one JVM), OIDC/JWKS mode, container images and Kubernetes
  manifests of this service (infra work-stream), PgBouncer.
