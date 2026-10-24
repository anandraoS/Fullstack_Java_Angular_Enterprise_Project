# ADR-0002: Kafka event design (envelope, topics, keys, versioning)

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

Services communicate state changes and saga commands asynchronously. We need one wire format, a topic layout that makes ordering and ownership clear, and a versioning strategy that lets producers and consumers deploy independently.

## Decision drivers

* Per-order ordering (an order must never see `InventoryReleased` before `InventoryReserved`).
* Single owner per topic (one producer service) so schemas have one authority.
* Independent deployment: old consumers must survive new producers.
* Debuggability: every record carries correlation and causation identifiers.

## Considered options

1. One topic per event type.
2. One topic per aggregate or interaction (chosen), several event types per topic, discriminated by `eventType`.
3. A single global topic.
4. Avro/Protobuf with a schema registry versus JSON with committed JSON Schema.

## Decision outcome

Topology (contract section 7): `oms.order.events.v1`, `oms.inventory.commands.v1`, `oms.inventory.events.v1`, `oms.fulfillment.commands.v1`, `oms.fulfillment.events.v1`, plus `<topic>.DLT`. Constants live in `Topics` (`services/platform-common/src/main/java/com/enterprise/oms/common/events`). Commands and events are separate topics so authorization, retention and consumers differ. Naming: `oms.<domain>.<events|commands>.v<major>`.

* Key: `orderId`, 3 partitions (replication 1 locally, 3 in prod). All events for one order land in one partition, so per-order ordering holds. There is no ordering guarantee across orders and none is needed.
* Envelope: `EventEnvelope<T>` with `eventId, eventType, schemaVersion, occurredAt, correlationId, causationId, aggregateType, aggregateId, producer, payload`. Kafka headers duplicate `correlationId, eventType, schemaVersion, eventId` (plus `traceparent`) so routing and tracing do not require parsing the body.
* Serialization: JSON via Jackson (`EventJson`), JSON Schema files committed under `specs/events`. Human-readable in DLTs and logs; no registry to operate on a laptop.
* Versioning: `schemaVersion` inside the envelope for additive change; the topic suffix `.v1` bumps only for breaking change, with dual publishing during migration. Details and a worked example are in `docs/architecture/event-schema-evolution.md`.
* Consumers use a tolerant reader (`FAIL_ON_UNKNOWN_PROPERTIES=false`). Unknown `eventType` is ignored and counted; malformed records are classified non-retryable (`NonRetryableEventException`, `MalformedEventException`) and go straight to the DLT. Routing is by `EventRouter` per consumer.
* Consumer group id equals the service name. Retries: `DefaultErrorHandler`, 4 attempts, 500 ms doubling to 5 s, then `DeadLetterPublishingRecoverer`.

### Consequences

* Good: strict per-order ordering, simple ownership, tolerant evolution, readable DLTs, no registry dependency.
* Bad: JSON is larger and unchecked at publish time unless contract tests run (they must); multiple event types per topic means consumers must filter (cheap, but each consumer discards events it does not handle); a hot order key cannot be spread across partitions; changing partition count later reshuffles keys.
* Not claimed: Kafka itself does not provide exactly-once across the database and Kafka. Delivery is at-least-once with idempotent consumers; see ADR-0005 and `docs/architecture/delivery-semantics.md`.

## Alternatives rejected

* One topic per event type: 15+ topics for four services, loses cross-event ordering for one order (reserved and released could arrive out of order on different topics).
* Single global topic: all consumers read everything, no per-topic ACLs, noisy lag metrics.
* Avro/Protobuf plus registry: stronger compatibility enforcement, but extra infrastructure that cannot run in the no-Docker local stack; JSON Schema plus contract tests gives most of the safety. Revisit if the number of producers grows.

## Links

* Contract section 7; `services/platform-common` packages `events`, `messaging`
* `docs/architecture/event-schema-evolution.md`, `docs/architecture/delivery-semantics.md`
* ADR-0005, ADR-0009
