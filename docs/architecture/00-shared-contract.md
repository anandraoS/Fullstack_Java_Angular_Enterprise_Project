# Shared Contract — Enterprise Order Management and Fulfillment Platform (OMS)

This is the single source of truth that every module (backend services, gateway, Angular UI, infra, docs) must follow.
If you need to deviate, change THIS file in the same edit and say so in your report. All data is synthetic.

## 1. Toolchain and versions (pinned)

| Item | Value |
|---|---|
| Java | 17 (use `C:\Users\sahukari.rao\.jdks\graalvm-jdk-17.0.12`; never export JAVA_HOME globally; pass inline `JAVA_HOME=...` per command) |
| Maven | `~/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn` |
| Spring Boot | 3.5.16 (parent), Spring Cloud 2025.0.x BOM (check `~/.m2` for what is cached), springdoc-openapi 2.8.17 |
| Maven group / version | `com.enterprise.oms` / `1.0.0`; java package root `com.enterprise.oms.<module>` |
| Backend root | `services/pom.xml` is the reactor (modules: platform-common, platform-test-support, order-service, inventory-service, fulfillment-service, notification-service, api-gateway, local-stack). The gateway module lives physically in `gateway/` and is included via `<module>../gateway</module>`. |
| Frontend | Angular 20 + Angular Material + TypeScript, in `frontend/oms-ui`; second UI (ADR-0013): React 19 + Redux Toolkit + MUI + TypeScript, in `frontend/oms-ui-react` (same contract, same e2e specs) |
| DB | PostgreSQL 16 in Compose/K8s. Local/test: H2 in `MODE=PostgreSQL` using the SAME Flyway SQL (write portable SQL; no pg-only types in migrations). Testcontainers PostgreSQL/Kafka tests are annotated `@Testcontainers(disabledWithoutDocker = true)` (Docker is NOT installed on the dev machine, so they are skipped there; report that honestly). |
| Kafka | Compose: `apache/kafka:3.9.x` KRaft. Local/test: `spring-kafka-test` EmbeddedKafka (KRaft). |
| Redis | Used ONLY for gateway/service rate limiting (bucket store) and optional product-read cache; every use must degrade to in-memory when `oms.redis.enabled=false` (the local default). |

## 2. Ports (local run, no Docker)

| Component | Port |
|---|---|
| api-gateway | 18080 |
| order-service | 18081 |
| inventory-service | 18082 |
| fulfillment-service | 18083 |
| notification-service | 18084 |
| embedded Kafka (local-stack) | 19092 |
| Angular dev server | 4200 (proxy `/api` -> 18080) |
| React dev server / built UI (second front end, ADR-0013) | 4201 (proxy `/api` -> 18080); `frontend/oms-ui-react` |
| management/actuator | same port as service, path `/actuator` |

## 3. Service boundaries and data ownership

* **order-service** — customers, orders, order_items, order_status_history, saga_instances, saga_step_log, idempotency_keys, outbox, processed_events, audit_events. Orchestrates the saga (PRIMARY pattern: orchestration; choreography discussed in ADR).
* **inventory-service** — products, stock (`on_hand`, `reserved`, `version`), inventory_reservations (+items), stock_movements, outbox, processed_events, audit_events. Owns product catalog and reservations.
* **fulfillment-service** — fulfillments, fulfillment_events, outbox, processed_events, audit_events. Simulated picking/packing/shipping.
* **notification-service** — notifications, notification_attempts, processed_events, audit_events. Consumes events only; records simulated notifications.
* **api-gateway** — Spring Cloud Gateway (reactive). JWT validation at the edge, CORS, rate limiting, correlation-id injection, route table, dev token endpoint (profile `dev`).
* Every service has its OWN schema/database (`order_db`, `inventory_db`, `fulfillment_db`, `notification_db` on one PG instance in Compose; separate in-memory H2 per service locally). No cross-service DB access.

## 4. Roles, auth, identities

* JWT (HS256 in demo; resource-server config is OIDC compatible: `oms.security.jwt.issuer-uri` / `jwk-set-uri` switch to RS256/JWKS). Claims: `sub`, `roles` (array of `CUSTOMER|OPERATOR|ADMIN|SERVICE`), `customerId` (UUID, for CUSTOMER), `iss=oms-dev-issuer`, `aud=oms-api`, `exp`, `iat`, `jti`.
* Secret: env `OMS_JWT_SECRET` (>= 32 bytes). NO default in prod profile (startup fails if missing). `scripts/run-local.sh` generates a random one per run. Tests generate theirs at runtime. NEVER hard-code a secret.
* Dev token endpoint (gateway, profile `dev` only): `POST /api/v1/auth/token` body `{ "username": "...", "password": "..." }` → `{ "accessToken", "tokenType":"Bearer", "expiresIn":3600, "username", "roles":[...], "customerId": "..."|null }`. Demo users: `customer1` (CUSTOMER, customerId = seed customer 1), `customer2` (CUSTOMER, seed customer 2), `operator1` (OPERATOR), `admin1` (ADMIN). Password for all demo users comes from env `OMS_DEMO_PASSWORD` (run-local.sh generates and PRINTS it; tests use a test constant). No default password in code.
* Service-to-service: services call each other (order → inventory REST validation) with a short-lived SERVICE JWT minted from the same secret (`roles:["SERVICE"]`, `sub:"svc-order-service"`), plus `X-Correlation-Id`. `/api/v1/internal/**` endpoints require `SERVICE` role and are NOT routed by the gateway.
* Authorization matrix (backend-authoritative; use method security + resource-level checks):
  * CUSTOMER: create orders only for own `customerId`; read/cancel only own orders; read own customer; read products/availability; read own notifications. Other customers' orders → **404** (not 403) to avoid enumeration.
  * OPERATOR: read all orders/customers/inventory/fulfillment/notifications/audit/ops; adjust stock; retry failed orders/fulfillments/notifications; cancel any order.
  * ADMIN: everything incl. product create/update/deactivate, customer create, reconciliation, DLT inspection.
  * SERVICE: `/api/v1/internal/**` only.

## 5. REST conventions

* Base path `/api/v1`. Versioning in URL; additive-only changes within v1 (documented in `docs/architecture/api-versioning.md`).
* Headers: `X-Correlation-Id` (accept or generate UUID; echo on response; put in MDC key `correlationId`), `Idempotency-Key` (required on `POST /orders`, 8–64 chars `[A-Za-z0-9_-]`).
* Errors: RFC 7807 `application/problem+json`: `{ "type":"https://oms.example/problems/<code-kebab>", "title", "status", "detail", "instance", "code":"<UPPER_SNAKE>", "correlationId", "timestamp", "errors":[{"field","message"}] }`. Codes include `VALIDATION_FAILED, NOT_FOUND, FORBIDDEN, UNAUTHENTICATED, CONFLICT, INVALID_STATE_TRANSITION, INSUFFICIENT_STOCK, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_REQUIRED, RATE_LIMITED, PAYLOAD_TOO_LARGE, UPSTREAM_UNAVAILABLE, INTERNAL_ERROR`. No stack traces or SQL in responses.
* Pagination: query `page` (0-based), `size` (default 20, max 100), `sort=field,asc|desc`. Response `{ "content":[...], "page":0, "size":20, "totalElements":N, "totalPages":M }`.
* Dates ISO-8601 UTC strings. Money: decimal string-safe `BigDecimal` serialized as JSON number with scale 2, `currency` = `USD`.
* Limits: `server.tomcat.max-http-form-post-size`, `spring.servlet.multipart` off, max JSON body 64 KB via filter (413 `PAYLOAD_TOO_LARGE`); outbound REST timeouts connect 2s / read 3s; gateway response timeout 10s.

### 5.1 order-service (via gateway)

| Method & path | Roles | Notes |
|---|---|---|
| `POST /api/v1/customers` | ADMIN, OPERATOR | `{name, email, tier?}` → 201 customer |
| `GET /api/v1/customers/{id}` | self(CUSTOMER), OPERATOR, ADMIN | |
| `GET /api/v1/customers?q=&page&size` | OPERATOR, ADMIN | |
| `POST /api/v1/orders` | CUSTOMER(own), OPERATOR, ADMIN | body `{customerId, items:[{productId, quantity}], shippingAddress:{line1,city,postalCode,country}}`; header `Idempotency-Key` required. → **202** + `Location`, body = Order (status `CREATED`). Replay same key+same body → 202 with the original body and header `Idempotent-Replay: true`. Same key + different body → 422 `IDEMPOTENCY_KEY_REUSED`. Missing key → 400 `IDEMPOTENCY_KEY_REQUIRED`. Items: 1–50 lines, quantity 1–1000, no duplicate productId. |
| `GET /api/v1/orders?status=&customerId=&from=&to=&q=&page&size&sort=` | CUSTOMER(own only, forced filter), OPERATOR, ADMIN | |
| `GET /api/v1/orders/{id}` | owner, OPERATOR, ADMIN | |
| `GET /api/v1/orders/{id}/history` | same | status history list |
| `POST /api/v1/orders/{id}/cancel` | owner, OPERATOR, ADMIN | `{reason}`; allowed from CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED; else 409 `INVALID_STATE_TRANSITION` |
| `GET /api/v1/admin/orders/failed?page&size` | OPERATOR, ADMIN | REJECTED, FAILED, COMPENSATION_PENDING-stuck |
| `POST /api/v1/admin/orders/{id}/retry` | OPERATOR, ADMIN | retries the current saga step (idempotent) |
| `GET /api/v1/admin/saga/{orderId}` | OPERATOR, ADMIN | saga instance + step log |
| `POST /api/v1/admin/reconciliation/run` | ADMIN | runs reconciliation now → `{examined, repaired, details[]}` |
| `GET /api/v1/ops/messaging` | OPERATOR, ADMIN | see §5.5 |
| `GET /api/v1/ops/audit?entityType=&entityId=&page&size` | OPERATOR, ADMIN | |

Order JSON: `{id, customerId, status, statusReason, totalAmount, currency, items:[{productId, sku, name, quantity, unitPrice, lineTotal}], shippingAddress:{...}, correlationId, createdAt, updatedAt, version}`.
History item: `{fromStatus, toStatus, reason, actor, occurredAt}`.
Saga JSON: `{orderId, sagaId, state, currentStep, attempts, startedAt, updatedAt, lastError, steps:[{step, status, attempt, detail, at}]}`.

### 5.2 inventory-service

| Method & path | Roles |
|---|---|
| `GET /api/v1/products?q=&category=&inStock=&page&size&sort=` | any authenticated |
| `GET /api/v1/products/{id}` | any authenticated |
| `POST /api/v1/products` / `PUT /api/v1/products/{id}` / `DELETE /api/v1/products/{id}` (deactivate) | ADMIN |
| `GET /api/v1/inventory?sku=&lowStock=&page&size` | OPERATOR, ADMIN |
| `GET /api/v1/inventory/{productId}` | OPERATOR, ADMIN (customers see `available` via products) |
| `PUT /api/v1/inventory/{productId}/stock` `{delta, reason}` | OPERATOR, ADMIN (writes stock_movement + audit; rejects result < reserved) |
| `GET /api/v1/inventory/reservations?orderId=&status=&page&size` | OPERATOR, ADMIN |
| `GET /api/v1/inventory/{productId}/movements?page&size` (added by inventory work-stream: stock movement ledger, newest first) | OPERATOR, ADMIN |
| `POST /api/v1/internal/products/validate` `{items:[{productId, quantity}]}` → `{valid, lines:[{productId, sku, name, unitPrice, available, active, ok, reason}]}` | SERVICE |
| `GET /api/v1/ops/messaging`, `GET /api/v1/ops/audit` | OPERATOR, ADMIN |
Product JSON: `{id, sku, name, description, category, price, currency, active, onHand, reserved, available, version}` (stock fields present for OPERATOR/ADMIN; customers get `available` only). **Clarified by the inventory work-stream:** `onHand`, `reserved` and `version` (= product row version) are staff-only fields (omitted, not null, for customers); customers never see deactivated products (list hides them, GET by id is 404); `POST /products` body also accepts optional `initialStock` (default 0); `PUT /products/{id}` accepts an optional `version` precondition (mismatch = 409 `CONFLICT`) and the SKU is immutable; `DELETE` deactivates idempotently and returns 200 with the Product JSON.
Reservation JSON: `{id, orderId, status(RESERVED|COMMITTED|RELEASED|EXPIRED|FAILED), items:[{productId, sku, quantity}], expiresAt, createdAt, updatedAt, releaseReason}` plus the additive optional fields `failureReason` (FAILED only: `INSUFFICIENT_STOCK|UNKNOWN_PRODUCT|PRODUCT_INACTIVE`) and `shippedAt`. Validate line `reason` is one of `NOT_FOUND|INACTIVE|INSUFFICIENT_STOCK` (null when `ok`); duplicate product lines are summed before the stock comparison.

### 5.3 fulfillment-service

`GET /api/v1/fulfillments?orderId=&status=&page&size` (OPERATOR, ADMIN; CUSTOMER not exposed), `GET /api/v1/fulfillments/{id}`, `POST /api/v1/admin/fulfillments/{id}/retry`, `GET /api/v1/ops/messaging`, `GET /api/v1/ops/audit`.
Fulfillment: `{id, orderId, status(PENDING|PICKING|PACKED|SHIPPED|COMPLETED|FAILED), trackingNumber, attempts, failureReason, events:[{status, at}], createdAt, updatedAt}`.
Simulation: after `StartFulfillment` it advances PICKING→PACKED→SHIPPED→COMPLETED on a scheduler with configurable step delay (`oms.fulfillment.step-delay-ms`, default 1500; tests set 0/small). **Deterministic failure hook:** shipping city `FAILTOWN` always fails (`FulfillmentFailed`); `oms.fulfillment.failure-rate` (default 0.0) for random failure.

### 5.4 notification-service

`GET /api/v1/notifications?customerId=&orderId=&status=&page&size` (CUSTOMER: own customerId forced; OPERATOR, ADMIN: all), `POST /api/v1/admin/notifications/{id}/retry`, `GET /api/v1/ops/messaging`, `GET /api/v1/ops/audit`.
Notification: `{id, customerId, orderId, channel(EMAIL|SMS simulated), template, subject, status(PENDING|SENT|FAILED|RETRYING), attempts, lastError, correlationId, sourceEventId, createdAt, sentAt}`. Delivery simulation: recipient email domain `fail.example.test` fails first N attempts (`oms.notification.simulated-failures`, default 2) then succeeds; retry with backoff; never contacts a real provider. Unique (`source_event_id`, `template`) → duplicate-event protection.

### 5.5 Operational endpoints (every service)

`GET /api/v1/ops/messaging` → `{service, consumerGroup, topics:[{topic, partitions, consumerLag, committedOffsets}], processedEvents, duplicatesIgnored, retries, deadLettered, outboxPending, lastProcessedAt, dltTopics:[...]}`. Lag via Kafka `AdminClient`; if unavailable return `null` lag and `"lagAvailable": false` (never fail the endpoint).
Gateway route rewrite: `/api/v1/ops/{order|inventory|fulfillment|notification}/**` → `lb://svc` path `/api/v1/ops/**` (e.g. `GET /api/v1/ops/order/messaging` → order-service `/api/v1/ops/messaging`).
Gateway also exposes `GET /api/v1/ops/health` (aggregate: calls each service `/actuator/health/readiness`, OPERATOR/ADMIN).
**Response shapes the UI depends on (added with the frontend; backends must match):**
* `GET /api/v1/ops/health` → `{ "status": "UP|DOWN|DEGRADED", "services": { "order": {"status":"UP|DOWN", "detail": null|"..."}, "inventory": {...}, "fulfillment": {...}, "notification": {...} } }` (always HTTP 200; a down service is reported in the body, never as an error).
* `GET /api/v1/ops/{svc}/audit?entityType=&entityId=&page&size` → pagination envelope of `{ id, entityType, entityId, action, actor, correlationId, details, occurredAt }` (newest first). The UI queries `entityType=Order&entityId=<orderId>` on `order` for the failed-order investigation, so order-service must record order audit events with `entityType="Order"`.
* `GET /api/v1/customers[?q=&page&size]` items and `GET /api/v1/customers/{id}` → `{ id, name, email, tier, createdAt }`.
* `GET /api/v1/admin/orders/failed` → pagination envelope of Order JSON. `POST /api/v1/admin/orders/{id}/retry`, `POST /api/v1/admin/fulfillments/{id}/retry`, `POST /api/v1/admin/notifications/{id}/retry` → any 2xx JSON body (the UI ignores it and re-fetches).
* `GET /api/v1/inventory` items use Product JSON with stock fields; `PUT /api/v1/inventory/{productId}/stock` returns the updated Product JSON; a result below `reserved` → 409 `CONFLICT`.
* `POST /api/v1/orders/{id}/cancel` → 200 with the updated Order JSON (the UI re-fetches anyway).
* `POST /api/v1/auth/token` failure → 401 problem+json (the UI shows a generic message and never echoes `detail`).
* Gateway CORS: the UI is served same-origin (nginx or `ng serve` proxy forwards `/api`), so no CORS is required for it; expose `Idempotent-Replay`, `Location`, `X-Correlation-Id`, `Retry-After` via `Access-Control-Expose-Headers` if a cross-origin UI is ever used.
* Frontend runtime config: the UI reads `config.json` (`{ "apiBaseUrl": "/api/v1", "pollIntervalMs", "pollMaxIntervalMs" }`) at startup; in the nginx image it is generated from env `OMS_API_BASE_URL`, and `/api/` is proxied to env `API_GATEWAY_URL`.
Actuator: `/actuator/health/{liveness,readiness}` and `/actuator/prometheus` (unauthenticated on management port only in prod via K8s network policy; in demo permitAll for health, prometheus requires no auth but is not routed through the gateway).

Gateway routes (static URIs from properties, not Eureka): `/api/v1/customers/**, /api/v1/orders/**, /api/v1/admin/orders/**, /api/v1/admin/saga/**, /api/v1/admin/reconciliation/**` → order; `/api/v1/products/**, /api/v1/inventory/**` → inventory; `/api/v1/fulfillments/**, /api/v1/admin/fulfillments/**` → fulfillment; `/api/v1/notifications/**, /api/v1/admin/notifications/**` → notification; `/api/v1/ops/{svc}/**` as above; `/api/v1/auth/token` local. OpenAPI: each service serves `/v3/api-docs`; committed exports in `specs/openapi/<service>.json`.

## 6. Order state machine

States: `CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED, FULFILLED, REJECTED, CANCELLED, COMPENSATION_PENDING, FAILED`. Terminal: `FULFILLED, REJECTED, CANCELLED, FAILED`.

| From | To | Trigger |
|---|---|---|
| CREATED | VALIDATED | validation OK (sync REST to inventory `/internal/products/validate`, circuit-breaker + retry) |
| CREATED | REJECTED | validation says invalid product/inactive/price mismatch |
| VALIDATED | INVENTORY_RESERVED | event `InventoryReserved` |
| VALIDATED | REJECTED | event `InventoryReservationFailed` (insufficient stock) |
| INVENTORY_RESERVED | CONFIRMED | orchestrator confirms, publishes `OrderConfirmed` + command `StartFulfillment` |
| CONFIRMED | FULFILLED | event `FulfillmentCompleted` → publish `OrderFulfilled` |
| CONFIRMED | COMPENSATION_PENDING | event `FulfillmentFailed` (after fulfillment's own retries) → command `ReleaseInventory` |
| CREATED, VALIDATED | CANCELLED | customer/operator cancel (if a reservation later arrives, orchestrator issues `ReleaseInventory`, order stays CANCELLED) |
| INVENTORY_RESERVED, CONFIRMED | COMPENSATION_PENDING | cancel → command `ReleaseInventory` |
| COMPENSATION_PENDING | CANCELLED | `InventoryReleased` and saga reason = CANCELLATION |
| COMPENSATION_PENDING | FAILED | `InventoryReleased` and saga reason = FULFILLMENT_FAILURE (or compensation retries exhausted → stays COMPENSATION_PENDING flagged for manual/reconciliation; admin retry available) |
| any non-terminal | FAILED | saga retries exhausted for non-compensable step (e.g. validation upstream down > max attempts) |
Everything else → `InvalidStateTransitionException` (409). Transition logic is a pure domain class (`OrderStateMachine`) with unit tests covering every valid and invalid pair. Each transition writes `order_status_history`, `saga_step_log`, `audit_events` and (when applicable) an outbox event in ONE DB transaction. Transitions are idempotent: applying an already-applied event again is a no-op.

## 7. Kafka topology and events

Topics (partitions 3, replication 1 locally / 3 prod; key = `orderId`):

| Topic | Owner (producer) | Consumers (group) | Events |
|---|---|---|---|
| `oms.order.events.v1` | order-service | notification (`notification-service`), inventory (`inventory-service`, only `OrderConfirmed`,`OrderCancelled`) | `OrderCreated, OrderConfirmed, OrderCancelled, OrderRejected, OrderFulfilled, OrderFailed` |
| `oms.inventory.commands.v1` | order-service | inventory (`inventory-service`) | `ReserveInventory, ReleaseInventory` |
| `oms.inventory.events.v1` | inventory-service | order (`order-service`) | `InventoryReserved, InventoryReservationFailed, InventoryReleased` |
| `oms.fulfillment.commands.v1` | order-service | fulfillment (`fulfillment-service`) | `StartFulfillment` |
| `oms.fulfillment.events.v1` | fulfillment-service | order (`order-service`), inventory (`inventory-service`, `FulfillmentCompleted` → ship stock), notification | `FulfillmentStarted, FulfillmentCompleted, FulfillmentFailed` |
| `<topic>.DLT` | the failing consumer | operators | dead-lettered records with original headers + `x-exception-*` |
Consumer group id = service name. Keys are orderId so per-order ordering holds within a partition.

Envelope (JSON, Jackson, class `EventEnvelope<T>` in platform-common): `{ "eventId":"uuid", "eventType":"OrderCreated", "schemaVersion":1, "occurredAt":"ISO", "correlationId":"uuid", "causationId":"uuid|null", "aggregateType":"Order", "aggregateId":"<orderId>", "producer":"order-service", "payload":{...} }` Kafka headers: `correlationId`, `eventType`, `schemaVersion`, `eventId`. Consumers use tolerant reader (`FAIL_ON_UNKNOWN_PROPERTIES=false`); unknown `eventType` → ignored + counted; malformed → DLT after non-retryable classification (no retries for deserialization/validation errors). JSON Schemas in `specs/events/*.schema.json`.

Payloads (field names are normative):
* `OrderCreated`: `{orderId, customerId, customerEmail, totalAmount, currency, items:[{productId, sku, quantity, unitPrice}], shippingCity}`
* `ReserveInventory` (command): `{orderId, items:[{productId, quantity}], reservationTtlSeconds}`; `ReleaseInventory`: `{orderId, reason}` (reason: `CANCELLATION|FULFILLMENT_FAILURE|EXPIRED`)
* `InventoryReserved`: `{orderId, reservationId, items:[{productId, quantity}], expiresAt}`; `InventoryReservationFailed`: `{orderId, reason, shortages:[{productId, requested, available}]}`; `InventoryReleased`: `{orderId, reservationId, reason}`
* `OrderConfirmed`: `{orderId, customerId, customerEmail, totalAmount}`; `StartFulfillment`: `{orderId, shippingAddress:{...}, items:[{productId, sku, quantity}]}`
* `FulfillmentStarted`: `{orderId, fulfillmentId}`; `FulfillmentCompleted`: `{orderId, fulfillmentId, trackingNumber}`; `FulfillmentFailed`: `{orderId, fulfillmentId, reason, attempts}`
* `OrderCancelled`: `{orderId, customerId, customerEmail, reason}`; `OrderRejected`: `{orderId, customerId, customerEmail, reason}`; `OrderFulfilled`: `{orderId, customerId, customerEmail, trackingNumber}`; `OrderFailed`: `{orderId, customerId, customerEmail, reason}`

Delivery semantics (document, do not overclaim): producers use the **transactional outbox** (row written in the same DB tx as state change; a relay publishes with `acks=all`, `enable.idempotence=true`, marks `published_at`) → at-least-once. Consumers are **idempotent** via `processed_events(consumer, event_id)` unique constraint inserted in the same tx as the business effect (+ state-machine idempotency) → effectively-once *business* effect. Manual-ack / `AckMode.RECORD`, `DefaultErrorHandler` with exponential backoff (4 attempts: 500ms ×2 up to 5s) then `DeadLetterPublishingRecoverer` → `<topic>.DLT`; non-retryable: deserialization, validation, `IllegalArgumentException`. Metrics (Micrometer, all services): `oms.events.processed{service,topic,type}`, `oms.events.duplicates`, `oms.events.retries`, `oms.events.dead_lettered`, `oms.outbox.pending` gauge, `oms.order.processing.duration` (timer; CREATED→terminal, order-service), `oms.saga.active` gauge, `oms.saga.compensations`.

Reservation lifecycle (inventory): `RESERVED` (counts in `reserved`, TTL `oms.inventory.reservation-ttl-seconds` default 900) → `COMMITTED` on `OrderConfirmed` (TTL cleared) → stock shipped (`on_hand -= q; reserved -= q`) on `FulfillmentCompleted`; `RELEASED` on `ReleaseInventory`/`OrderCancelled` (reserved -= q); `EXPIRED` by scheduler if still `RESERVED` after `expires_at` (reserved -= q, emits `InventoryReleased{reason:EXPIRED}`; order-service then moves an order still in VALIDATED/INVENTORY_RESERVED to FAILED). Duplicate `ReserveInventory` for the same orderId is a no-op returning the original outcome (unique `order_id` on reservations). Concurrency: atomic conditional SQL `UPDATE stock SET reserved = reserved + :q, version = version + 1 WHERE product_id = :id AND on_hand - reserved >= :q` (affected rows = 0 → insufficient) — demonstrated alongside `@Version` optimistic locking on admin stock edits (retry on `ObjectOptimisticLockingFailureException`) and a pessimistic `SELECT ... FOR UPDATE` variant documented/benchmarked in docs; items locked/updated in sorted productId order to avoid deadlocks. All-or-nothing per reservation (one tx).

## 8. Saga

* Orchestrated by `OrderSagaOrchestrator` in order-service; table `saga_instances(order_id unique, state, current_step, attempts, last_error, reason, version, timestamps)` and `saga_step_log`.
* Steps: `VALIDATE → RESERVE_INVENTORY → CONFIRM_ORDER → FULFILL`; compensation: `RELEASE_INVENTORY`.
* Retry: validation REST retry (Resilience4j, 3 attempts) + saga-level retry counter; scheduled `SagaRecoveryJob` re-drives steps stuck longer than `oms.saga.stuck-after-seconds` (default 60; tests small): re-sends the idempotent command; after `oms.saga.max-attempts` (default 5) → FAILED (non-compensable) or flagged COMPENSATION_PENDING + alert metric (compensable).
* Reconciliation (`ReconciliationService`): finds inconsistencies (order terminal but reservation not released; CONFIRMED order with no fulfillment progress; CANCELLED order with reservation RESERVED), repairs by issuing compensating commands; exposed via admin endpoint and scheduled job; results audited.
* Explicitly NOT a distributed ACID transaction: states are eventually consistent; compensations are semantic.

## 9. Seed data (synthetic; Flyway `V1000__seed_*` (CHANGED from V9: seed rows need the service tables created by V10+) in `dev` profile via `spring.flyway.locations` including `classpath:db/seed`)

Fixed UUIDs: customer1 `c0000000-0000-0000-0000-000000000001` (Acme Industrial Supply, `procurement@acme.example.test`), customer2 `c0000000-0000-0000-0000-000000000002` (Globex Logistics, `orders@globex.example.test`), customer3 `...0003` (Initech Failures, `ops@fail.example.test`). 20 products with SKUs `SKU-1001`..`SKU-1020`, product UUID pattern `a0000000-0000-0000-0000-0000000010NN` (NN = 01..20; CHANGED from `p0000000-...` because `p` is not a hex digit and is not a valid UUID), stock between 0 and 500 (include SKU-1020 with stock 0 and SKU-1019 with stock 3 for insufficient-stock demos; SKU-1001 stock 500).

## 10. Observability

JSON logs (logback + `logstash-logback-encoder`, fields: `timestamp, level, service, correlationId, traceId, spanId, logger, message`; profile `json-logs` default in containers, plain console locally), never log tokens/passwords/emails in full (mask emails `a***@domain`). Micrometer Prometheus registry; OpenTelemetry via `micrometer-tracing-bridge-otel` + OTLP exporter (`management.otlp.tracing.endpoint`, off locally by default, sampling probability configurable). Kafka header propagation of `traceparent`/`correlationId`.

## 11. Repo layout (physical)

```
services/{pom.xml, platform-common, platform-test-support, order-service, inventory-service, fulfillment-service, notification-service, local-stack}
gateway/                      (maven module api-gateway)
frontend/oms-ui
infrastructure/{docker,compose,kubernetes,ci,cloud}
observability/{prometheus,grafana,otel,loki-or-elk}
tests/{e2e,api-collection,bdd}
performance/ (k6)
ai-engineering/  specs/  docs/  scripts/  tools/
```
Compose files at `infrastructure/compose/docker-compose.yml`; K8s base + overlays (Kustomize) at `infrastructure/kubernetes/`; GitHub Actions workflow at `.github/workflows/ci.yml` (repo root) mirrored by `infrastructure/ci/Jenkinsfile` optional.

## 12. Backend foundation conventions (added by the platform-common work-stream; normative)

Details and usage snippets: `services/platform-common/README.md`. Deviations/clarifications of sections above:

* **Reactor**: `services/pom.xml` lists platform-common, platform-test-support, local-stack unconditionally; order/inventory/fulfillment/notification and `../gateway` are in file-activated profiles (active when `<module>/pom.xml` exists) because Maven validates module directories even with `-pl`. Versions: Boot 3.5.16, Spring Cloud **2025.0.0** (not pre-cached; resolved from Central), springdoc 2.8.17, resilience4j **2.2.0** (must equal the BOM managed by Spring Cloud), logstash-logback-encoder 8.0, JaCoCo 0.8.13. SpotBugs/Checkstyle are NOT configured (not available offline).
* **Flyway versions**: `V1..V8` reserved for platform-common (`classpath:db/common`, file `V1__outbox_processed_audit.sql`: tables `outbox_events`, `processed_events`, `audit_events`); service schema migrations start at `V10`; seed data `V1000+`. Default `spring.flyway.locations=classpath:db/common,classpath:db/migration` is supplied by platform-common (override must keep `classpath:db/common`). Every service (including notification) gets the three tables.
* **Error codes**: contract list plus `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE`, `BAD_REQUEST` (framework-raised). `errors` is always present (possibly empty). Malformed `Idempotency-Key` format -> 400 `VALIDATION_FAILED` (absent/blank -> `IDEMPOTENCY_KEY_REQUIRED`).
* **Paging**: `size` outside 1..100 or `page < 0` or non-whitelisted `sort` field -> 400 `VALIDATION_FAILED` (never silently clamped). `sort=field,dir` accepted as one value, repeated `sort` params allowed.
* **DLT headers**: besides Spring's `kafka_dlt-*` headers the recoverer adds `x-exception-class`, `x-exception-message`, `x-original-topic`, `x-original-partition`, `x-original-offset`. DLT records are produced with partitioner-chosen partition (key kept).
* **`/api/v1/ops/messaging` and `/api/v1/ops/audit`** are provided by platform-common itself (controllers auto-registered, OPERATOR/ADMIN); services only configure `oms.messaging.topics`.
* **Kafka beans**: `omsKafkaTemplate` (`KafkaTemplate<String,String>`, acks=all, idempotent) and listener container factory `omsKafkaListenerContainerFactory` (group = `oms.messaging.consumer-group` or `spring.application.name`). Embedded KRaft in `local-stack` is pinned to 19092 via listener override (spring-kafka-test ignores `kafkaPorts` in KRaft mode).
* **Security**: HS256 secret from `oms.security.jwt.secret` / env `OMS_JWT_SECRET`; fail-fast when absent or < 32 bytes unless `oms.security.jwt.allow-ephemeral-secret=true` (tests / single-process only). In OIDC mode (`issuer-uri` / `jwk-set-uri`) the HS256 beans (`JwtTokenFactory`, `ServiceTokenProvider`) are not created.
* **fulfillment-service clarifications** (added with the service): `FulfillmentStarted` is published when `StartFulfillment` is accepted (fulfillment created in `PENDING`), not when picking begins; internal attempts happen when leaving `PENDING` (`oms.fulfillment.max-attempts`, default 3; city `FAILTOWN` fails every attempt) and `FulfillmentFailed{attempts}` is published exactly once after the last one; `POST /api/v1/admin/fulfillments/{id}/retry` only accepts `FAILED` (-> `PENDING`, attempts reset; else 409 `INVALID_STATE_TRANSITION`) and a later success publishes `FulfillmentCompleted` without a second `FulfillmentStarted` (order-service must tolerate this late event); tracking number = `OMS-` + 12 upper-case hex of SHA-256(orderId); CUSTOMER tokens get 403 on all fulfillment endpoints; extra env/props `oms.fulfillment.{poll-interval-ms,batch-size,scheduler-enabled}`, `FULFILLMENT_DB_URL|USERNAME|PASSWORD` (required in profile `prod`).
* **notification-service clarifications**: fulfillment events carry only `orderId`, so the service keeps `order_contacts(order_id, customer_id, customer_email)` fed from every order event (OrderCreated first); a fulfillment event whose contact is unknown yet is retried by the container (backoff) and then dead-lettered. One notification per event (template per event type: `ORDER_CREATED|CONFIRMED|REJECTED|CANCELLED|FULFILLED|FAILED`, `FULFILLMENT_STARTED|COMPLETED|FAILED`), channel `EMAIL`. For CUSTOMER tokens `customerId` is a forced filter: a different `customerId` query value is ignored, not honoured; a customer token without `customerId` claim gets 403. Admin retry only accepts `FAILED` (-> `PENDING`, fresh attempt budget; else 409). Props: `oms.notification.{max-attempts(5),simulated-failures(2),backoff-initial-ms(1000),backoff-multiplier(2.0),backoff-max-ms(30000),poll-interval-ms,batch-size,scheduler-enabled}`, `NOTIFICATION_DB_URL|USERNAME|PASSWORD` (required in profile `prod`). The recipient address is never returned by the API and only logged masked.
* **Gateway (reactive)** must exclude servlet/JPA/Kafka starters if it depends on platform-common (see README "Gateway usage"); it only needs `JwtSecret`, `JwtTokenFactory`, `LogMasking`, `CorrelationIds`.

## 13. order-service clarifications (added by the order-service work-stream; normative for the other services, the gateway and the UI)

Details: `services/order-service/README.md`. Clarifications and deviations of sections 5-8:

* **Validation is asynchronous.** `POST /orders` persists the order (status `CREATED`) and answers **202** immediately; the saga then calls inventory `POST /api/v1/internal/products/validate` on its own thread. Until validation has priced the order the Order JSON has `totalAmount = 0.00` and `sku/name/unitPrice/lineTotal = null` on the items. A validation response in which ANY line has `ok=false` (unknown, inactive, or not enough stock: `reason` is copied into `statusReason`) moves the order to `REJECTED` (`OrderRejected`, no reservation attempted). `InventoryReservationFailed` still rejects an order that validated but lost the race for stock.
* **`OrderCreated` timing (deviation from section 7 wording).** Its payload carries the price snapshot, so `OrderCreated` is published when the order is priced (CREATED -> VALIDATED), in the same transaction and outbox batch as `ReserveInventory`. An order rejected at validation emits only `OrderRejected` (consumers, e.g. notification, must accept `OrderRejected` for an order they never saw `OrderCreated` for), and an order cancelled before validation emits only `OrderCancelled`.
* **Other events:** `OrderConfirmed` + `StartFulfillment` are emitted in ONE transaction when `InventoryReserved` arrives (INVENTORY_RESERVED -> CONFIRMED happens inside that transaction; both history rows are written). `OrderCancelled` is emitted when the order reaches `CANCELLED`: immediately for CREATED/VALIDATED, after `InventoryReleased` for the compensation path. `OrderFailed` carries the human-readable reason.
* **What order-service expects from inventory** (idempotency is the receiver's job): `ReserveInventory` for an orderId that already has a reservation re-emits/returns the original outcome event; `ReleaseInventory` for an unknown or already released reservation is a no-op (order-service sends it defensively and for reconciliation) and SHOULD still answer `InventoryReleased` when a reservation existed; `InventoryReleased{reason:EXPIRED}` is the expiry signal (VALIDATED/INVENTORY_RESERVED -> FAILED); the validate endpoint returns one line per requested product.
* **Idempotency-Key scope** is the `customerId` of the request body (unique `(scope, key)`); the fingerprint is a SHA-256 of a canonical form (customerId, lines sorted by productId, trimmed address) so whitespace, property order and line order do not matter. A replay returns the ORIGINAL 202 body (a snapshot), not the order's current state. Keys are kept `oms.idempotency.retention` (24h). A request that fails validation/authorization does not consume its key.
* **Rate limiting:** `POST /orders` is limited per caller (JWT subject); exceeding it is **429 `RATE_LIMITED` + `Retry-After`**. In-memory token bucket by default, Redis-backed only when `oms.redis.enabled=true` (degrades to in-memory if Redis fails).
* **Authorization order:** role rules are enforced at URL level before request binding (403 before 400); resource-level rules return 404 for other customers' orders/customers. A `CUSTOMER` token whose `customerId` differs from the body's gets **403** on `POST /orders`; a `CUSTOMER` calling `GET /orders?customerId=<other>` gets their own orders (the filter is forced).
* **Saga `state` values:** `RUNNING, COMPENSATING, COMPLETED, ABORTED, COMPENSATED, FAILED, NEEDS_ATTENTION` (NEEDS_ATTENTION = compensation retries exhausted; the order stays `COMPENSATION_PENDING`, admin retry or reconciliation re-opens it). Step log `status` values: `STARTED, SUCCEEDED, FAILED, RETRIED, COMPENSATED, SKIPPED`.
* **Admin:** `POST /admin/orders/{id}/retry` returns the Order JSON; for a terminal order it is **409 `INVALID_STATE_TRANSITION`** (terminal means terminal; a customer places a new order). `GET /admin/orders/failed` = `REJECTED`, `FAILED`, and `COMPENSATION_PENDING` with no progress for `oms.saga.stuck-after-seconds`.
* **Smaller points:** cancel body is optional (`{reason?}`, default "Cancelled by customer|staff"); customer `tier` is `STANDARD|SILVER|GOLD|PLATINUM` (default STANDARD), emails are stored lower-case and unique (409 `CONFLICT`); order search `q` = order id, or case-insensitive PREFIX of customer email / shipping city; sortable fields `createdAt, updatedAt, status, totalAmount` (default `createdAt,desc`); `GET /orders/{id}/history` returns the bare array; dev seed (`V1000`) contains only the three customers (orders are created through the API); order `version` is the optimistic-locking counter.
* **Build note:** `-pl order-service` needs `com.enterprise.oms:oms-services-parent:1.0.0` in `~/.m2`; it is installed with `mvn -f services/pom.xml -N install` (the non-recursive parent-pom install; platform-common's own `install` also does this when run with `-am`).
* **Gateway implementation notes (added by the gateway work-stream; details in `gateway/README.md`)**: besides the exclusions above the gateway also excludes `OmsCoreAutoConfiguration` (its `ProblemWriter` needs the servlet API) via `spring.autoconfigure.exclude`, so list it together with the other `Oms*` auto-configurations. Property names: `oms.gateway.routes.{order|inventory|fulfillment|notification}.uri` (env `OMS_GATEWAY_ROUTES_<SVC>_URI`), `oms.gateway.cors.allowed-origins` (env `OMS_GATEWAY_CORS_ALLOWED_ORIGINS`, exact origins, `*` rejected), `oms.gateway.{rate-limit,circuit-breaker,health,throttle}.*`, `oms.gateway.max-body-bytes`, `oms.redis.enabled`, `oms.demo.password` (profile `dev`, bound from `OMS_DEMO_PASSWORD`; startup fails in `dev` when unset or < 8 chars). Edge behaviour: `/api/v1/internal/**` is answered 404 before authentication; SERVICE-role tokens are rejected at the edge (403) because services call each other directly; edge role rules are defense-in-depth only; `POST /api/v1/auth/token` exists only in profile `dev` (otherwise 401/404); upstream 4xx/5xx responses pass through unchanged and only connect failures/timeouts/open circuits become 503 `UPSTREAM_UNAVAILABLE` (with `Retry-After: 5`); 429 carries `Retry-After`. `/v3/api-docs` is NOT routed through the gateway; `scripts/export-openapi.mjs` reads each service directly.

## 14. inventory-service clarifications (added by the inventory-service work-stream; normative for order-service, UI, ops)

Details: `services/inventory-service/README.md`. Deviations / refinements of sections 5.2, 7 and 9:

* **Seed product ids (deviation from section 9 as first written):** `a0000000-0000-0000-0000-0000000010NN` (NN = 01..20). The earlier `p0000000-...` pattern is not a valid UUID (`p` is not a hex digit). Anyone seeding orders against inventory must use the `a000...` ids (SKU-1001 -> `...001001`, SKU-1020 -> `...001020`). Seed lives in `classpath:db/seed/V1000__seed_products.sql`, loaded only in profile `dev`.
* **Reservation slot / duplicates:** every `orderId` owns exactly ONE row in `inventory_reservations` whatever the outcome (unique `order_id`), claimed first with a plain INSERT inside a JDBC savepoint (not `ON CONFLICT`, which H2's PostgreSQL mode rejects). A repeated `ReserveInventory` (new or same `eventId`) returns the ORIGINAL outcome and RE-EMITS the matching event with the original `reservationId`: `InventoryReserved` (RESERVED/COMMITTED), `InventoryReservationFailed` (FAILED, original shortages), `InventoryReleased` (RELEASED/EXPIRED). So a consumer may see the same outcome event more than once and must be idempotent (order-service is).
* **`InventoryReservationFailed.reason`** is one of `INSUFFICIENT_STOCK | UNKNOWN_PRODUCT | PRODUCT_INACTIVE`; `shortages[]` lists every short/unknown/inactive product with `available` 0 for unknown/inactive. All-or-nothing: a failed reservation holds no stock.
* **Release semantics:** `ReleaseInventory` (command) and `OrderCancelled` (event) release a RESERVED or COMMITTED reservation (reason `CANCELLATION` for the event). The command is always answered with `InventoryReleased` (first time, replay, or nothing-to-release); the event path emits only when it actually releases. When no reservation exists yet, the release writes a RELEASED **tombstone** for that `orderId` (its id is returned as `reservationId` in the answer) so a late or duplicate `ReserveInventory` of that order can never take stock. Goods already shipped (`shippedAt` set) cannot be released (no-op + warning).
* **Commit race:** `OrderConfirmed` for a reservation that is already EXPIRED/RELEASED/FAILED is NOT re-reserved: it is logged, counted (`oms.inventory.reservation.late_commit`) and left to reconciliation. Expiry vs confirm is decided by guarded UPDATEs; exactly one wins.
* **Ship:** `FulfillmentCompleted` sets `shipped_at` (exactly once) and does `on_hand -= q; reserved -= q`; the reservation status stays `COMMITTED`.
* **Strategies:** `oms.inventory.reservation-strategy` = `atomic` (default) | `pessimistic` | `optimistic` (env `OMS_RESERVATION_STRATEGY`); behaviour is identical, performance under contention differs (README benchmark).
* **Metrics added:** `oms.inventory.reservations{outcome=reserved|failed|duplicate|committed|released|expired|shipped}`, `oms.inventory.reservation.conflicts`, `oms.inventory.reservation.duration{strategy,outcome}`, `oms.inventory.reservation.late_commit`; `http.server.requests` publishes percentile histograms.
* **Optional Redis product cache** (`oms.redis.enabled`, default false): caches `GET /products/{id}` only; never used for reservation decisions, validate or inventory endpoints.
* **JSON strictness:** inventory binds request JSON with `accept-float-as-int=false` (`{"delta":1.5}` is 400, not silently 1); unknown properties are still ignored.

## 15. Integration-pass clarifications (added by the integration work-stream, 2026-10-18; normative)

Evidence and rationale: `docs/validation/validation-report.md`, `docs/validation/integration-defects.md`.

* **Gateway concurrency limit (new properties):** `oms.gateway.bulkhead.max-concurrent-calls` (env `OMS_GATEWAY_BULKHEAD_MAX_CALLS`, default 200) and `oms.gateway.bulkhead.max-wait` (default 500ms) bound the concurrent in-flight calls PER DOWNSTREAM SERVICE; overflow is shed as 503 `UPSTREAM_UNAVAILABLE` (+ `Retry-After`). The Spring Cloud default of 25 was an unintended cap.
* **Order JSON of a REJECTED order** (section 13) keeps `sku/name/unitPrice/lineTotal = null` for its items and `totalAmount = 0.00`; clients (the Angular UI) must render nulls.
* **Metrics defaults (all services):** failure-path counters `oms.events.retries`, `oms.events.dead_lettered`, `oms.events.duplicates` are pre-registered at 0 per topic of `oms.messaging.topics`; Kafka consumer client metrics (`kafka_consumer_*`, incl. `kafka_consumer_fetch_manager_records_lag_max`) are exported; `http.server.requests` and `spring.kafka.listener` publish histogram buckets (gateway included for `http.server.requests`). `scripts/check-metric-names.mjs` asserts that every metric used by `observability/` exists in the live scrape.
* **Logging:** order-service logs every order transition and creation, fulfillment-service logs acceptance and each lifecycle step, all with the order's correlation id (MDC `correlationId`), without e-mail addresses.
* **Profiles / docs exposure:** `prod` disables `springdoc.api-docs` and `springdoc.swagger-ui` in every service; the Kubernetes base ConfigMap no longer selects an environment profile (`json-logs` only; overlays choose `dev,json-logs` or `prod,json-logs`). The gateway has no OpenAPI document of its own endpoints at runtime; `specs/openapi/api-gateway.json` is generated by `scripts/gen-gateway-openapi.mjs`.
* **Local run helpers:** `scripts/run-local.sh [--no-build] [--json-logs] [--ui [angular|react|both]]` (`OMS_GATEWAY_PORT` moves the gateway when 18080 is taken by another project; React UI on 4201) writes the per-run secrets to `.run/secrets.env` (mode 600, git-ignored, removed by `stop-local.sh`) so `scripts/service-ctl.sh stop|start|restart <service>` can restart one JVM with the same secret.

