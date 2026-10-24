# ADR-0004: Database per service

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

Each service owns a distinct set of aggregates (contract section 3). Sharing tables would let teams bypass APIs, couple releases through schema changes and make "who owns this row" unanswerable.

## Decision drivers

* Enforced encapsulation: schema changes are local.
* Independent migration cadence (Flyway per service).
* Failure isolation: a long inventory transaction must not hold locks that order queries wait on.
* One PostgreSQL instance in Compose/K8s is acceptable for cost; logical separation is mandatory.

## Considered options

1. Shared database, shared schema.
2. Shared PostgreSQL instance, separate database and credentials per service (chosen for Compose/K8s).
3. Separate PostgreSQL clusters per service.

## Decision outcome

`order_db`, `inventory_db`, `fulfillment_db`, `notification_db` created by `infrastructure/compose/postgres/init/01-create-databases.sh`, each with its own user. Locally each service uses its own in-memory H2 in `MODE=PostgreSQL` running the same Flyway SQL. Flyway layout: `V1..V8` reserved for `platform-common` (`outbox_events`, `processed_events`, `audit_events` in `classpath:db/common`), service migrations from `V10`, seed data from `V1000`. Migrations are written portably (no PostgreSQL-only types) so H2 and PostgreSQL agree.

Data needed from another service is obtained by (a) event payloads that carry what the consumer needs (for example `customerEmail` in `OrderCreated`, so notification never calls order), (b) the single synchronous internal validate call, or (c) local denormalized copies (order_items snapshot `sku, name, unitPrice` at order time). Never by a join across databases.

### Consequences

* Good: clear ownership, local transactions stay short, independent schema evolution and backup/restore.
* Bad: no cross-service joins or constraints (referential integrity across services is eventual and verified by reconciliation); reporting needs events or read models; duplicated reference data; one shared PostgreSQL instance is still a shared failure and resource domain (noisy neighbour); H2 PostgreSQL mode is an approximation, so PostgreSQL-specific behaviour is only verified by Testcontainers tests, which are skipped on the dev machine because Docker is not installed there.

## Alternatives rejected

* Shared schema: fastest to build, but turns services into a distributed monolith and makes the saga unnecessary and therefore undemonstrable.
* Separate clusters per service: correct for strict isolation, but triples operational cost for a demo; the logical separation preserves the design and can be split later by changing JDBC URLs.

## Links

* Contract sections 3, 9, 12; `services/platform-common/src/main/resources/db/common`
* `infrastructure/compose/postgres/init`
* ADR-0001, ADR-0009
