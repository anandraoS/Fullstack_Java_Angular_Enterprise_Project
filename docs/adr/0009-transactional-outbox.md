# ADR-0009: Transactional outbox for event publication

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

A state change and its event must not diverge. Writing to the database and then to Kafka is a dual write: a crash between them either loses the event (state changed, nothing published) or publishes a phantom (event sent, transaction rolled back).

## Decision drivers

* No lost events after a committed state change.
* No events for rolled-back changes.
* Works with plain PostgreSQL/H2 and Spring Kafka.
* Honest delivery guarantee.

## Considered options

1. Dual write (save, then send).
2. Transactional outbox with a polling relay (chosen).
3. Outbox with CDC (Debezium).
4. Kafka transactions plus a database transaction (best-effort 2PC).

## Decision outcome

Table `outbox_events` (Flyway `V1__outbox_processed_audit.sql` in `services/platform-common/src/main/resources/db/common`) exists in every producing service. `OutboxService` inserts the row in the same transaction as the state change, including saga step log and audit rows. `OutboxRelay` (package `com.enterprise.oms.common.outbox`, config `OutboxProperties`) polls unpublished rows in order, publishes through `omsKafkaTemplate` (`acks=all`, `enable.idempotence=true`, key = aggregate id), waits for the broker acknowledgement, then sets `published_at`. Gauge `oms.outbox.pending` and the `outboxPending` field of `/api/v1/ops/messaging` expose backlog.

Guarantee: at-least-once publication. If the relay crashes after Kafka acknowledged but before `published_at` is committed, the row is published again. Consumers therefore must be idempotent (ADR-0005). The producer-side idempotence setting removes duplicates caused by producer retries within a session; it does not remove the relay re-publish case. Kafka does not give exactly-once across the database and Kafka here, and this design does not claim it.

### Consequences

* Good: no lost or phantom events, simple to reason about and test, survives broker outages (rows accumulate, backlog gauge alerts), keeps ordering per aggregate when the relay sends in insertion order for a key.
* Bad: added latency (poll interval), extra writes and table growth (needs a purge of old published rows), the relay is another thing to scale and monitor, polling load on the database, ordering across multiple relay instances needs care (single active relay per service or row-level claim with `FOR UPDATE SKIP LOCKED` on PostgreSQL), duplicates are possible by design.

## Alternatives rejected

* Dual write: loses or invents events on every crash window.
* CDC (Debezium): lower latency and no polling, but needs Kafka Connect and PostgreSQL logical replication that cannot run in the Docker-less local stack; a valid production upgrade path because the outbox table shape stays the same.
* Kafka plus DB transaction coordination: no standard cross-resource atomic commit; complexity without a guarantee we could defend.

## Links

* Contract sections 7 and 12; `services/platform-common` package `outbox`
* ADR-0002, ADR-0005; `docs/architecture/delivery-semantics.md`
