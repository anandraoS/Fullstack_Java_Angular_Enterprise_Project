# notification-service

Turns order and fulfillment events into **simulated** customer notifications (no real e-mail/SMS provider is ever contacted),
with duplicate-event protection, retry with exponential backoff and an operator retry. Contract:
`docs/architecture/00-shared-contract.md` (sections 5.4, 5.5, 7, 12). Port **18084**, Java 17, Spring Boot 3.5.16, JPA + Flyway,
H2 (PostgreSQL mode) locally, PostgreSQL in Compose/K8s. Consume-only: `oms.outbox.enabled=false` (it publishes nothing).

## Responsibilities
* Consume `oms.order.events.v1` (OrderCreated, OrderConfirmed, OrderRejected, OrderCancelled, OrderFulfilled, OrderFailed) and
  `oms.fulfillment.events.v1` (FulfillmentStarted, FulfillmentCompleted, FulfillmentFailed), idempotently.
* Map each event to a template + subject and persist one `notifications` row per (event, template).
* Deliver through the `NotificationSender` port (`SimulatedSender`), retrying with backoff; record every attempt.
* Let customers read their own notifications; let operators see everything and retry FAILED ones.

## Event to template mapping
| Event | Template | Subject (short order id = first 8 chars) |
|---|---|---|
| OrderCreated | `ORDER_CREATED` | Order `<id>` received |
| OrderConfirmed | `ORDER_CONFIRMED` | Order `<id>` confirmed |
| OrderRejected | `ORDER_REJECTED` | Order `<id>` could not be accepted |
| OrderCancelled | `ORDER_CANCELLED` | Order `<id>` cancelled |
| OrderFulfilled | `ORDER_FULFILLED` | Order `<id>` delivered - tracking `<tn>` |
| OrderFailed | `ORDER_FAILED` | Order `<id>` failed |
| FulfillmentStarted | `FULFILLMENT_STARTED` | We are preparing order `<id>` |
| FulfillmentCompleted | `FULFILLMENT_COMPLETED` | Order `<id>` has shipped - tracking `<tn>` |
| FulfillmentFailed | `FULFILLMENT_FAILED` | Problem preparing order `<id>` |

Only channel `EMAIL` is produced; `SMS` exists in the enum for the contract. Other event types on these topics are ignored and counted
(`oms.events.ignored`). Note: one order can legitimately produce both `FULFILLMENT_COMPLETED` and `ORDER_FULFILLED`.

## Who is the customer? (`order_contacts`)
Order events carry `customerId` and `customerEmail`; **fulfillment events carry only `orderId`**. Every order event therefore upserts
`order_contacts(order_id, customer_id, customer_email)` (table fed from OrderCreated first and refreshed by later order events), and
fulfillment events resolve the customer through it. If a fulfillment event arrives before the order's contact is known, the handler throws a
*retryable* `ContactUnavailableException`: the container retries with backoff (4 attempts) and only then dead-letters. The e-mail address is
stored **only** in `order_contacts`; notifications reference the order, the sender resolves the recipient at delivery time, the API never returns it.

## APIs (base `/api/v1`, JWT bearer)
| Method and path | Roles | Notes |
|---|---|---|
| `GET /notifications?customerId=&orderId=&status=&page&size&sort=` | CUSTOMER (own only), OPERATOR, ADMIN | **CUSTOMER: `customerId` is forced to the token's `customerId`**; a different `customerId` parameter is ignored (never widens the result); token without `customerId` -> 403; sort whitelist `createdAt, sentAt, status, attempts, template`; bad status/paging/sort -> 400 `VALIDATION_FAILED` |
| `POST /admin/notifications/{id}/retry` | OPERATOR, ADMIN | FAILED -> PENDING with a fresh attempt budget; other states 409 `INVALID_STATE_TRANSITION`; unknown 404 |
| `GET /ops/messaging`, `GET /ops/audit` | OPERATOR, ADMIN | provided by platform-common |

Notification JSON: `{id, customerId, orderId, channel, template, subject, status(PENDING|SENT|FAILED|RETRYING), attempts, lastError, correlationId, sourceEventId, createdAt, sentAt}`.
OpenAPI at `/v3/api-docs` (Swagger UI disabled in profile `prod`).

## Delivery simulation and retry
* `NotificationSender` is the port; `SimulatedSender` is the only implementation (declare your own bean to replace it). Recipients in domain
  `fail.example.test` (seed customer 3) fail their first `oms.notification.simulated-failures` attempts (default 2) of a delivery round, then succeed; everyone else succeeds at once.
* `NotificationDeliveryJob` polls every `poll-interval-ms` and makes one attempt for each due `PENDING`/`RETRYING` row (own transaction per attempt,
  optimistic locking so several instances are safe). Failure -> `RETRYING`, next attempt after `min(backoff-max, backoff-initial * multiplier^(n-1))`;
  `max-attempts` reached -> `FAILED`. Success -> `SENT` (`sent_at`). Every attempt is a row in `notification_attempts` (`FAILURE`/`SUCCESS`, sanitized error).
* Operator retry resets `attempts` to 0 (history stays in `notification_attempts`) and re-queues immediately.
* The simulated sender runs inside the attempt transaction; a real provider call should be moved outside it (send, then record).

State machine (`NotificationStatus`): `PENDING -> SENT|RETRYING|FAILED`, `RETRYING -> SENT|RETRYING|FAILED`, `FAILED -> PENDING` (operator), `SENT` terminal.

## Configuration
| Property (env) | Default | Meaning |
|---|---|---|
| `oms.notification.max-attempts` (`OMS_NOTIFICATION_MAX_ATTEMPTS`) | 5 | attempts before FAILED |
| `oms.notification.simulated-failures` (`OMS_NOTIFICATION_SIMULATED_FAILURES`) | 2 | first N attempts fail for `fail.example.test` |
| `oms.notification.backoff-initial-ms` / `backoff-multiplier` / `backoff-max-ms` | 1000 / 2.0 / 30000 | backoff (env `OMS_NOTIFICATION_BACKOFF_*`) |
| `oms.notification.poll-interval-ms`, `batch-size`, `scheduler-enabled` | 250, 25, true | scheduler tuning / kill switch |
| `oms.outbox.enabled` | false | notification-service never publishes |
| `OMS_JWT_SECRET` | none (required, >= 32 bytes) | HS256 secret |
| `OMS_KAFKA_BOOTSTRAP` | `localhost:19092` | Kafka |
| `NOTIFICATION_DB_URL / _USERNAME / _PASSWORD` | H2 in memory / `sa` / empty | JDBC; **required with profile `prod`** (no defaults) |
| `SERVER_PORT` | 18084 | |

Run: `OMS_JWT_SECRET=<32+ random chars> java -jar target/notification-service-1.0.0.jar`.

## Data ownership (own database `notification_db`)
| Table | Migration | Purpose |
|---|---|---|
| `notifications` | `V10__notification_tables.sql` | one row per notification; **`UNIQUE(source_event_id, template)`**; status, attempts, last_error, correlation_id, next_attempt_at, version |
| `notification_attempts` | V10 | one row per delivery attempt |
| `order_contacts` | V10 | order -> customerId + e-mail (see above) |
| `processed_events`, `audit_events` (+ unused `outbox_events`) | platform-common `V1` | consumer idempotency, audit trail |

## Topics
Consumes `oms.order.events.v1` and `oms.fulfillment.events.v1` (group `notification-service`, key = orderId). Produces nothing.
Failed processing: 4 attempts with exponential backoff then `<topic>.DLT` (`x-exception-*` headers). Straight to the DLT (non-retryable): malformed JSON,
unbindable payload, missing `orderId`, invalid/missing e-mail address in an order event.

## Failure modes
| Situation | Behaviour |
|---|---|
| Same event delivered twice | `processed_events` PK skips it (`oms.events.duplicates`) |
| Replay after processed markers were purged / offsets reset | `unique(source_event_id, template)` check skips it (`oms.notification.duplicates`) |
| Fulfillment event before OrderCreated | retryable `ContactUnavailableException`, then DLT |
| Provider failing | RETRYING with backoff, FAILED after `max-attempts`, operator retry |
| Process crash | state and `next_attempt_at` are in the DB; the scheduler resumes |
| Poison message | DLT, partition keeps flowing |

## Observability and privacy
Metrics: `oms.notification.created{template,channel}`, `.sent`, `.retries`, `.failed`, `.duplicates`, `.manual_retries`, gauge `oms.notification.pending`,
plus platform `oms.events.*`. `correlationId` goes event -> notification row -> MDC during delivery (and into logs). **E-mail addresses are only logged masked**
(`b***@domain`); errors stored/returned are sanitized; subjects contain no address. Audit entries (`entityType=Notification`): CREATED, ATTEMPT_FAILED, SENT, FAILED, RETRY_REQUESTED.

## Scaling
Stateless; scale horizontally (consumer group `notification-service`, 3 partitions per topic). Several scheduler instances are safe (optimistic lock; the
loser skips). The due-query is index-backed (`ix_notifications_due`).

## Security boundaries
Resource server (HS256 demo / OIDC capable), stateless bearer-only (CSRF off deliberately). CUSTOMER sees only own rows (forced filter, token-derived),
admin retry is staff only, SERVICE tokens are not allowed on these APIs. No provider credentials exist in this service. Problem+json responses, no stack traces.

## Tests (`mvn verify`)
`NotificationUnitTest` (status rules, templates, backoff, sender, entity), `NotificationServiceTest` (Mockito), `NotificationApiTest` (MockMvc authz incl. customer
cross-access, validation, retry rules), `NotificationKafkaTest` (EmbeddedKafka: all 9 event types, duplicate, replay, retry flow to SENT, FAILED after max + operator retry, poison -> DLT,
early fulfillment event, unknown type, correlation id, e-mail masking), `NotificationBddScenariosTest` (mirror of `tests/bdd/notification-scenarios.feature`),
`NotificationPostgresIT` (Testcontainers, skipped without Docker).
