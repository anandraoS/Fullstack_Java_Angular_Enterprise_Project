# Kafka and event rules

Load when: producing or consuming events. Normative: contract section 7; docs `docs/adr/0002`, `0009`,
`docs/architecture/event-schema-evolution.md`, `delivery-semantics.md`.

## Producing
* Never `kafkaTemplate.send` from business code. Call `OutboxService.append(topic, key, envelope)` inside the business
  transaction; `OutboxRelay` publishes (`acks=all`, idempotent producer).
* Envelope: `EventEnvelope<T>` with `eventId` (new UUID), `eventType`, `schemaVersion`, `occurredAt`, `correlationId`,
  `causationId` (id of the event/command that caused this one), `aggregateType`, `aggregateId`, `producer`.
* Key = `orderId` (per-order ordering within a partition). Topics are `oms.<domain>.<events|commands>.v1`.
* Payload field names are normative (contract section 7). Add optional fields only; never repurpose a field.
  New required data = new `schemaVersion` or new event type; breaking = new topic version `.v2` with dual publish.
* Events carry what consumers need (ids, amounts, `customerEmail` for notification); do not put secrets or full
  addresses where not required.

## Consuming
* Wrap every effect in `IdempotentEventProcessor.processOnce(consumer, topic, envelope, effect)`.
* Tolerant reader: unknown properties ignored; unknown `eventType` ignored and counted, never an error.
* Errors: transient -> throw (container retries 4x then DLT); malformed/validation -> throw `NonRetryableEventException`
  (straight to `<topic>.DLT`). Never catch-and-ack (flawed-ai-examples/09).
* Do not call remote services inside the listener unless unavoidable; if you must, use timeouts and make the effect
  retryable.
* A command handler for a duplicate request returns the ORIGINAL outcome (e.g. duplicate `ReserveInventory` for an order).
* Metrics are not optional: `oms.events.processed`, `.duplicates`, `.retries`, `.dead_lettered`, `oms.outbox.pending`.

## Schemas and tests
* JSON Schema per event in `specs/events/*.schema.json` (exists: 15 event schemas plus the envelope; validated against live Kafka records by `scripts/validate-events.mjs`); consumer tests deserialize fixtures that contain an extra unknown field.
* Test the duplicate, the poison, and the out-of-order case for every handler.
* Delivery claim to use in docs: at-least-once + idempotent consumers = effectively-once business effect. Do not write "exactly-once".
