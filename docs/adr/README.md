# Architecture Decision Records

MADR-style records for the OMS platform. New ADRs start from [0000-template.md](0000-template.md). Never edit an accepted ADR's decision; supersede it with a new one and update the status line.

| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-microservices-vs-modular-monolith.md) | Four services plus a gateway instead of a modular monolith | Accepted | 2026-10 |
| [0002](0002-kafka-event-design.md) | Kafka event design (envelope, topics, keys, versioning) | Accepted | 2026-10 |
| [0003](0003-saga-orchestration-vs-choreography.md) | Orchestrated saga for order processing | Accepted | 2026-10 |
| [0004](0004-database-per-service.md) | Database per service | Accepted | 2026-10 |
| [0005](0005-idempotency-strategy.md) | Idempotency at API, consumer and state machine | Accepted | 2026-10 |
| [0006](0006-concurrency-control.md) | Concurrency control for stock reservation | Accepted | 2026-10 |
| [0007](0007-api-versioning.md) | URL-path API versioning, additive-only evolution | Accepted | 2026-10 |
| [0008](0008-ci-cd-and-deployment.md) | CI/CD pipeline and deployment model | Accepted | 2026-10 |
| [0009](0009-transactional-outbox.md) | Transactional outbox for event publication | Accepted | 2026-10 |
| [0010](0010-jwt-oidc-security-model.md) | JWT, OIDC-compatible resource servers, backend-authoritative authz | Accepted | 2026-10 |
| [0011](0011-observability-stack.md) | Observability stack (Prometheus, Grafana, Loki, Tempo, OTel) | Accepted | 2026-10 |
| [0012](0012-angular-state-management.md) | Angular state management with signals, not NgRx | Accepted | 2026-10 |
| [0013](0013-dual-frontend-angular-and-react.md) | Two front ends (Angular and React + Redux) on one backend contract | Accepted | 2026-10 |

Companion documents: [shared contract](../architecture/00-shared-contract.md), [API versioning](../architecture/api-versioning.md), [event schema evolution](../architecture/event-schema-evolution.md), [delivery semantics](../architecture/delivery-semantics.md), [saga and consistency](../architecture/saga-and-consistency.md), [Angular architecture](../architecture/angular-architecture.md), [React architecture](../architecture/react-architecture.md).

Reading order for interviews or onboarding: 0001, 0004, 0002, 0009, 0005, 0003, 0006, then the rest.
