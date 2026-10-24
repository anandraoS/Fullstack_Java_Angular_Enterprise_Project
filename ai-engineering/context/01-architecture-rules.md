# Architecture rules

Load when: adding a module, endpoint, cross-service call, or dependency. Decisions: `docs/adr/0001`..`0004`.

1. Four services plus gateway; do not add a fifth service without an ADR that names the data it owns and why a module in
   an existing service is insufficient (ADR-0001).
2. Database per service. No cross-service SQL, no shared tables, no shared JPA entities. The only shared code is
   `platform-common` (infrastructure: outbox, idempotency, errors, security, web), never domain entities.
3. Synchronous calls only where the caller needs the answer to proceed: order -> inventory `POST
   /api/v1/internal/products/validate`, with Resilience4j circuit breaker + retry, connect 2s / read 3s timeouts, SERVICE
   JWT. Everything else is asynchronous via Kafka.
4. Layering inside a service: `api` (controllers, DTOs) -> `service` (use cases, transactions) -> `domain` (entities,
   pure state machines, no Spring) -> `repo`. Controllers never touch repositories directly; the domain package must not
   import Spring web, Kafka or JPA repositories (entities may carry JPA annotations).
5. State machines are pure classes (`OrderStateMachine`, `FulfillmentStateMachine`): `(state, trigger) -> state` or
   `InvalidStateTransitionException`. Exhaustive unit tests over all state x trigger pairs.
6. Gateway is reactive and must not depend on servlet, JPA or Kafka starters. It validates JWT at the edge; services
   validate again. `/api/v1/internal/**` is never routed.
7. Cross-cutting concerns come from `platform-common` autoconfiguration (correlation id, problem+json, paging, ops
   endpoints). Do not re-implement them per service.
8. Config: every environment-dependent value is a property with a safe default or a startup failure; secrets via env.
9. Prefer extending an existing seam (new event type, new listener) over adding a new synchronous dependency.

Anti-patterns to reject in review: shared database, orchestrator calling another service's repository, "just one more
REST call" in a Kafka listener, business logic in controllers or in Kafka listeners, entity returned from a controller.
