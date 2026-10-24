## Principle

Service boundaries follow data ownership and rate of change, not technical layers. Each service owns its tables exclusively and exposes data only through its REST API or its events. No service reads another service's database.

## The four services and the gateway

| Component | Port (local) | Owns | Responsibility |
|---|---|---|---|
| api-gateway | 18080 | Routes, edge policy | JWT validation, CORS allow-list, rate limiting, request size limit, correlation id, circuit breaker, dev token endpoint (profile `dev`), aggregate health |
| order-service | 18081 | customers, orders, order_items, order_status_history, saga_instances, saga_step_log, idempotency_keys | Order API, `OrderStateMachine`, saga orchestration, recovery job, reconciliation |
| inventory-service | 18082 | products, stock, inventory_reservations (+items), stock_movements | Catalog, stock, atomic reservation, expiry, internal validation endpoint |
| fulfillment-service | 18083 | fulfillments, fulfillment_events | Simulated picking, packing, shipping, retry, failure injection |
| notification-service | 18084 | notifications, notification_attempts, order_contacts | Consumes events only, simulated delivery with backoff |
| platform-common (library) | n/a | Shared tables `outbox_events`, `processed_events`, `audit_events` in every service database | Outbox relay, idempotent consumer, audit, problem+json, JWT, correlation id, Micrometer, ops endpoints |

Every service database also contains the three shared tables from `services/platform-common/src/main/resources/db/common/V1__outbox_processed_audit.sql`, but each service has its own copy and its own rows. Flyway versions V1 to V8 are reserved for the shared library, service schemas start at V10, seed data at V1000 (dev profile only).

## Why four services and not one

The decision is recorded in ADR-0001. In short: the four capabilities have different consistency needs and different failure behaviour. Inventory must serialise on hot rows; fulfillment is slow and flaky by nature; notification is allowed to lag. Splitting them lets each fail and scale independently and makes the saga, outbox and idempotency patterns real instead of decorative. The cost is distributed-systems complexity, which is why the documents are explicit about eventual consistency. A modular monolith would be the right default for a small team with no independent scaling needs; ADR-0001 discusses that trade-off honestly.

## Synchronous versus asynchronous communication

| Interaction | Style | Reason |
|---|---|---|
| Browser to gateway to services | REST over HTTP | Request/response user interactions |
| order-service to inventory-service validation | REST (`POST /api/v1/internal/products/validate`) with SERVICE JWT, timeouts 2 s connect / 3 s read, Resilience4j retry and circuit breaker | The saga needs prices and availability to price the order before reserving |
| Reserve, release, confirm, ship, notify | Kafka commands and events | Decoupled, durable, replayable, naturally retried |

## Contracts between services

* REST contracts are published as OpenAPI (`specs/openapi/`, exported with `scripts/export-openapi.mjs`).
* Event contracts are JSON envelopes defined in `services/platform-common/src/main/java/com/enterprise/oms/common/events/EventEnvelope.java` with payload records in `EventPayloads.java` and topic names in `Topics.java`.
* Both are versioned additively; see the API versioning and event schema evolution documents.

## Service discovery and configuration: an honest note

This project does **not** use Eureka or Spring Cloud Config. Routes are static URIs from properties (`oms.gateway.routes.<svc>.uri`), because in Docker Compose and Kubernetes the platform already provides DNS-based service discovery (Compose service names, Kubernetes `Service` objects) and configuration (environment variables, ConfigMaps, Secrets). Adding a discovery server or config server would add two more stateful components without adding capability in these runtimes. Spring Cloud is used where it earns its place: Gateway for the edge and Resilience4j for fault tolerance. In an interview, say exactly this and add that Eureka/Config would be reasonable on a VM-based estate without an orchestrator.

## Shared library trade-off

`platform-common` removes boilerplate (outbox, idempotency, error format, security) but couples services to one library version. The mitigation is semantic versioning of the library, additive changes, and keeping domain logic out of it. A shared library is not a shared database, which is the coupling that matters most.

{{code:services/platform-common/src/main/java/com/enterprise/oms/common/events/EventEnvelope.java|public record EventEnvelope|18|java}}
