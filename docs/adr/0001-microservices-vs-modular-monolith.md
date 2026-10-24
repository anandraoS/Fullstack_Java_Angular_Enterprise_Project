# ADR-0001: Four services plus a gateway instead of a modular monolith

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

The OMS must demonstrate order intake, inventory reservation, simulated fulfillment and customer notification. Those four capabilities have different change rates, different scaling profiles (inventory reads and reservations are hot; notification is bursty and tolerates delay) and different failure behaviour (a notification outage must never block order acceptance). The project is also a reference for distributed-systems patterns (saga, outbox, idempotent consumers), which only exist as real problems once there is a network and more than one database.

Honest framing: a team of this size, with this traffic, would normally ship a modular monolith first. This repository deliberately chooses services because the learning and demonstration goals need the distributed failure modes to be real.

## Decision drivers

* Independent deployability and failure isolation for notification and fulfillment.
* Data ownership boundaries that are enforced by the runtime, not by convention (see ADR-0004).
* Need to demonstrate eventual consistency, saga and delivery semantics honestly.
* Operational cost must stay manageable on a developer laptop without Docker (embedded Kafka and H2, see `services/local-stack`).

## Considered options

1. Modular monolith (one deployable, module-per-capability, one schema with per-module table prefixes).
2. Four services plus an edge gateway (chosen).
3. Many fine-grained services (separate customer service, product service, payment service, and so on).

## Decision outcome

Option 2: `order-service`, `inventory-service`, `fulfillment-service`, `notification-service`, and an `api-gateway` (Spring Cloud Gateway, physically in `gateway/`). Service boundaries follow data ownership in contract section 3. Shared technical code (envelope, outbox, idempotency, security, error model) lives in the library `services/platform-common`, never shared business code and never shared entities.

Rules that keep this from becoming a distributed monolith:

* No synchronous call chains deeper than one hop. The only sync call is order to inventory product validation (`/api/v1/internal/products/validate`), protected by a circuit breaker, retry and timeouts (connect 2s, read 3s).
* All state changes that other services care about travel as events (ADR-0002) via the outbox (ADR-0009).
* No cross-service database access (ADR-0004).
* The library `platform-common` carries only cross-cutting infrastructure; changing it is a coordinated, versioned release (`1.0.0` across the reactor).

### Consequences

* Good: failure isolation (notification down does not stop orders), independent scaling (KEDA Kafka-lag scaling is configured in `infrastructure/kubernetes/keda`), clear ownership, realistic demonstration of saga/outbox/idempotency.
* Bad: eventual consistency everywhere (see `docs/architecture/saga-and-consistency.md`), higher operational surface (4 databases, Kafka, gateway), harder local debugging, duplicated boilerplate (mitigated by platform-common), end-to-end tests are slower and need orchestration. Distributed tracing becomes mandatory (ADR-0011).

### When to merge back into a modular monolith

Merge when most of these hold: a single team owns all four services; no service has ever scaled independently; more than half of releases touch two or more services together (lock-step deploys); the saga adds more incidents than it prevents; infrastructure cost or on-call burden dominates. Mechanically the merge is cheap if boundaries were respected: each service already owns its tables, so modules keep the same schemas, replace Kafka commands with in-process calls behind the same interfaces, and keep the outbox only for genuinely external consumers. The state machine and idempotency logic stay unchanged.

## Alternatives rejected

* Modular monolith: the better default for real teams, rejected here only because the reference goals (independent failure, real eventual consistency, per-service scaling) cannot be demonstrated in one process.
* Many fine-grained services: multiplies network hops and sagas without a business reason; customer data sits with orders, product data sits with stock because they change together.

## Links

* `docs/architecture/00-shared-contract.md` sections 3, 7, 8
* `services/platform-common`, `services/local-stack`, `gateway/`
* ADR-0003, ADR-0004, ADR-0009
