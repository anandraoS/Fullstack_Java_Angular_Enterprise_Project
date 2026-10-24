# fulfillment-service

Simulated warehouse: consumes `StartFulfillment` commands, walks each order through
`PENDING -> PICKING -> PACKED -> SHIPPED -> COMPLETED` (or `FAILED`) on a scheduler, and reports the outcome to the order
saga with `FulfillmentStarted / FulfillmentCompleted / FulfillmentFailed`. Contract: `docs/architecture/00-shared-contract.md`
(sections 5.3, 5.5, 7, 12). Port **18083**, Java 17, Spring Boot 3.5.16, JPA + Flyway, H2 (PostgreSQL mode) locally,
PostgreSQL in Compose/K8s.

## Responsibilities
* Idempotently create one fulfillment per order from `StartFulfillment` (unique `order_id`).
* Drive the lifecycle (step delay, internal retry attempts, deterministic and random failure hooks).
* Publish lifecycle events through the transactional outbox (state change + audit + outbox row in ONE DB transaction).
* Operator tooling: list/get fulfillments, retry a FAILED fulfillment, audit trail, messaging status.
* Not responsible for: orders, stock, notifications, any real carrier integration (everything is simulated).

## APIs (base `/api/v1`, JWT bearer; CUSTOMER tokens are never allowed here)
| Method and path | Roles | Notes |
|---|---|---|
| `GET /fulfillments?orderId=&status=&page&size&sort=` | OPERATOR, ADMIN | sort whitelist `createdAt, updatedAt, status, attempts`; bad status/paging/sort -> 400 `VALIDATION_FAILED` |
| `GET /fulfillments/{id}` | OPERATOR, ADMIN | 404 `NOT_FOUND` |
| `POST /admin/fulfillments/{id}/retry` | OPERATOR, ADMIN | FAILED -> PENDING, attempts reset; any other state 409 `INVALID_STATE_TRANSITION` |
| `GET /ops/messaging`, `GET /ops/audit` | OPERATOR, ADMIN | provided by platform-common (`oms.messaging.topics` configured here) |

Fulfillment JSON: `{id, orderId, status, trackingNumber, attempts, failureReason, events:[{status, at}], createdAt, updatedAt}`.
OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` (disabled in profile `prod`). Errors are RFC 7807 problem+json.

## Lifecycle and simulation
```
PENDING --attempt ok--> PICKING -> PACKED -> SHIPPED -> COMPLETED
   |  \--attempt fails, attempts < max: stay PENDING, retry after step delay
   \--attempt fails, attempts = max --> FAILED  (FulfillmentFailed{attempts})        FAILED --operator retry--> PENDING
```
* `FulfillmentStateMachine` is a pure class (every valid/invalid pair is unit-tested).
* An *attempt* happens when leaving PENDING. Shipping city `FAILTOWN` (case-insensitive) fails **every** attempt, so it always ends
  FAILED after `max-attempts`; `oms.fulfillment.failure-rate` adds a random per-attempt failure probability.
* `FulfillmentStarted` is published when the command is accepted (fulfillment created, PENDING); `FulfillmentCompleted`
  carries the tracking number; `FulfillmentFailed` is published exactly once, after the last internal attempt.
  An operator retry that later succeeds publishes `FulfillmentCompleted` (no second `Started`); the order saga has normally
  already compensated by then, so order-service must treat it as a late/ignorable event.
* Tracking number: `OMS-` + 12 upper-case hex chars of SHA-256(orderId); assigned when PACKED -> SHIPPED. Same order, same number.
* Scheduler `FulfillmentProgressJob` polls every `poll-interval-ms`, advances each due in-flight row by one step in its own transaction.
  Optimistic locking (`version`) makes concurrent instances safe (loser skips, next poll retries).

## Configuration
| Property (env) | Default | Meaning |
|---|---|---|
| `oms.fulfillment.step-delay-ms` (`OMS_FULFILLMENT_STEP_DELAY_MS`) | 1500 | delay between steps and between attempts (tests: 0/20) |
| `oms.fulfillment.failure-rate` (`OMS_FULFILLMENT_FAILURE_RATE`) | 0.0 | random failure probability per attempt |
| `oms.fulfillment.max-attempts` (`OMS_FULFILLMENT_MAX_ATTEMPTS`) | 3 | internal attempts before `FulfillmentFailed` |
| `oms.fulfillment.poll-interval-ms`, `batch-size`, `scheduler-enabled` | 250, 25, true | scheduler tuning / kill switch |
| `OMS_JWT_SECRET` | none (required, >= 32 bytes) | HS256 secret (startup fails without it) |
| `OMS_KAFKA_BOOTSTRAP` | `localhost:19092` | Kafka |
| `FULFILLMENT_DB_URL / _USERNAME / _PASSWORD` | H2 in memory / `sa` / empty | JDBC; **required with profile `prod`** (no defaults) |
| `SERVER_PORT` | 18083 | |

Run (local, embedded Kafka from `local-stack` running): `OMS_JWT_SECRET=<32+ random chars> java -jar target/fulfillment-service-1.0.0.jar`.

## Data ownership (own database `fulfillment_db`; no cross-service access)
| Table | Migration | Purpose |
|---|---|---|
| `fulfillments` | `V10__fulfillment_tables.sql` | one row per order; `UNIQUE(order_id)`; `status, attempts, tracking_number, failure_reason, next_step_at, version, correlation_id, source_event_id` |
| `fulfillment_events` | V10 | status history (`seq`, `status`, `occurred_at`) |
| `outbox_events`, `processed_events`, `audit_events` | platform-common `V1` | transactional outbox, consumer idempotency, audit trail |

## Topics
* Consumes `oms.fulfillment.commands.v1` (group `fulfillment-service`): `StartFulfillment`. Unknown types are ignored and counted.
* Produces `oms.fulfillment.events.v1` (key = orderId, via outbox relay): `FulfillmentStarted`, `FulfillmentCompleted`, `FulfillmentFailed`.
* Failed consumption: 4 attempts with exponential backoff, then `oms.fulfillment.commands.v1.DLT` (`x-exception-*` headers).
  Malformed JSON, unbindable payload, missing `orderId` or `shippingAddress.city` go straight to the DLT (non-retryable).

## Failure modes
| Situation | Behaviour |
|---|---|
| Duplicate delivery of the same event | `processed_events` PK skips it (counted `oms.events.duplicates`) |
| Second command for an order (new eventId) | `existsByOrderId` no-op, audited `DUPLICATE_START_IGNORED`, `oms.fulfillment.duplicate_commands`; unique constraint is the race backstop |
| Poison command | DLT, partition keeps flowing |
| Kafka down while publishing | rows stay in the outbox, relay retries (at-least-once; consumers are idempotent) |
| Process crash mid-lifecycle | state, attempts and `next_step_at` are in the DB; on restart the scheduler resumes. `FulfillmentRecovery` (ApplicationReady) audits `RESUMED_AFTER_RESTART` and re-arms any in-flight row without schedule |
| Two instances | both poll; optimistic lock lets one win per step; a lost race can at worst skip a poll |
| DB down | consumer retries then DLT; scheduler logs and retries next poll |

## Observability
Metrics: `oms.fulfillment.transitions{status}`, `oms.fulfillment.attempts.failed`, `oms.fulfillment.failed`, `oms.fulfillment.duplicate_commands`,
`oms.fulfillment.recovered`, gauge `oms.fulfillment.in_flight`, plus the platform `oms.events.*` and `oms.outbox.pending`.
Correlation id travels command -> fulfillment row -> outgoing events (MDC `correlationId`). Audit entries use `entityType=Fulfillment`.

## Scaling
Stateless service; scale horizontally. Commands are keyed by orderId (per-order ordering in a partition, 3 partitions);
the scheduler is safe to run in every replica; outbox relay tolerates several instances (duplicates are idempotent downstream).
The poll query is index-backed (`ix_fulfillments_status_due`).

## Security boundaries
Resource server (HS256 demo / OIDC capable), stateless, CSRF off deliberately (bearer-only). All business endpoints need
OPERATOR or ADMIN (CUSTOMER -> 403, no enumeration of orders). No PII is stored (shipping address only). Admin retry is audited
with the caller subject. Request body limit 64 KB, problem+json without stack traces.

## Tests (`mvn verify`)
`FulfillmentStateMachineTest`, `FulfillmentServiceTest` (Mockito), `FulfillmentApiTest` (MockMvc authz/validation),
`FulfillmentKafkaTest` (EmbeddedKafka: happy path, FAILTOWN failure event, duplicates, poison -> DLT, retry endpoint, restart recovery),
`FulfillmentBddScenariosTest` (mirror of `tests/bdd/fulfillment-scenarios.feature`), `FulfillmentPostgresIT`
(Testcontainers, skipped without Docker).
