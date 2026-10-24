# Event schema evolution

Decision record: [ADR-0002](../adr/0002-kafka-event-design.md). Normative events: [shared contract](00-shared-contract.md) section 7. Code: `services/platform-common/src/main/java/com/enterprise/oms/common/events` (`EventEnvelope`, `EventPayloads`, `EventTypes`, `Topics`, `EventJson`, `MalformedEventException`). JSON Schemas belong in `specs/events/*.schema.json`.

## 1. Building blocks

* `schemaVersion` (envelope field and Kafka header): integer, per event type, starts at 1. Incremented when the payload gains optional fields or a documented semantic refinement.
* Topic version suffix (`oms.order.events.v1`): major, incremented only for breaking change.
* Tolerant reader: consumers configure Jackson with `FAIL_ON_UNKNOWN_PROPERTIES=false`, read only what they need, treat missing optional fields as absent. Unknown `eventType` is ignored and counted. Structurally malformed records go to the DLT without retries.
* JSON Schema: producer side validates in tests against `specs/events`; schemas use `additionalProperties: true` so adding fields is compatible.

## 2. Rules

| Change | Compatible? | Action |
|---|---|---|
| Add optional payload field | Yes (backward and forward) | Bump `schemaVersion`, update schema, no topic change |
| Add new event type to existing topic | Yes if consumers ignore unknown types (they do) | Add to schema set and `EventTypes` |
| Make an optional field required | No | New topic version |
| Remove or rename a field | No | Add new field now, remove only at next topic major |
| Change a field type or unit | No | New field name, or new topic version |
| Change meaning of an existing field | No | New field name |
| Change the key (not orderId) | No | New topic version (partitioning changes ordering) |
| Add envelope field | Yes | Envelope is also tolerant |

## 3. Techniques

1. Add optional field: the default path. Producer writes it, old consumers ignore it, new consumers use `null` as "old producer".
2. Upcasting: when a consumer must understand several `schemaVersion`s of one type, convert older payloads to the newest in-memory shape at the edge (in the `EventRouter` handler for that type) and run only the newest logic. Example: `schemaVersion 1` lacks `shippingMethod`; the upcaster sets it to `STANDARD`.
3. New topic version with dual publish (breaking change): publish every event to both `...v1` and `...v2` (two outbox rows in one transaction) while consumers migrate; switch consumers one by one; stop v1 after all consumer groups' lag on v1 is zero and a retention period has passed; delete v1 topic last. The outbox makes dual publish atomic with the state change.
4. Replay safety: DLT and retained records keep their original `schemaVersion`; handlers must stay able to read every version still in retention.

## 4. Compatibility matrix

| Producer \ Consumer | Consumer reads v1 | Consumer reads v2 (new field used) |
|---|---|---|
| Producer writes v1 | OK | OK, new field absent, defaults apply (upcast) |
| Producer writes v2 | OK, extra field ignored (tolerant reader) | OK |

Deploy order for an additive change: consumers first is safest, but because both directions are compatible any order works. For a breaking change use dual publish.

## 5. Worked example: add `shippingMethod` to `OrderCreated`

Goal: customers choose `STANDARD` or `EXPRESS`; notification wants to mention it.

1. API: add optional `shippingMethod` to `POST /orders` (default `STANDARD`), additive per [api-versioning.md](api-versioning.md).
2. Order-service: persist the field (Flyway migration from `V10+`, nullable column default `STANDARD`), and add `shippingMethod` to the `OrderCreated` payload. Set envelope `schemaVersion` to 2 for `OrderCreated`. Payload after:

```json
{
  "eventId": "7d6f0c9e-0000-4000-8000-000000000001",
  "eventType": "OrderCreated",
  "schemaVersion": 2,
  "occurredAt": "2026-10-18T10:15:30Z",
  "aggregateType": "Order",
  "aggregateId": "<orderId>",
  "producer": "order-service",
  "payload": {
    "orderId": "<orderId>", "customerId": "<customerId>", "customerEmail": "a***@example.test",
    "totalAmount": 120.50, "currency": "USD",
    "items": [{"productId": "<id>", "sku": "SKU-1001", "quantity": 2, "unitPrice": 60.25}],
    "shippingCity": "Springfield",
    "shippingMethod": "EXPRESS"
  }
}
```

(Correlation fields omitted for brevity; the real envelope carries them.)

3. Schema: update `specs/events/OrderCreated.schema.json` with `shippingMethod` as an optional string enum-like field (not in `required`), `schemaVersion` allowing 1 and 2.
4. Inventory-service (consumes only `OrderConfirmed` and `OrderCancelled` from this topic) needs no change; it already ignores `OrderCreated`.
5. Notification-service: deploy the version that reads `shippingMethod`, treating absent as `STANDARD` (upcast). Records written by the old order-service (v1) and still in retention are handled.
6. Rollout: any order. If order-service deploys first, old notification ignores the field. If notification deploys first, it sees v1 events and defaults.
7. Tests: a contract test with a v1 fixture and a v2 fixture for each consumer; a producer test validating output against the schema.
8. Not needed: new topic, dual publish, consumer-group reset.

If instead we needed to rename `shippingCity` to a structured `shippingAddress`, that is breaking: add `shippingAddress` alongside `shippingCity` for at least one release (additive), migrate consumers, and remove `shippingCity` only when moving to `oms.order.events.v2` with dual publish.

## 6. Checklist for every event change

* Optional, defaulted, documented? Schema file updated in the same PR?
* `schemaVersion` bumped and fixtures for old and new added?
* Consumers verified against unknown fields and unknown event types?
* DLT inspection (`/api/v1/ops/messaging`, `*.DLT` topics) clean after rollout?
