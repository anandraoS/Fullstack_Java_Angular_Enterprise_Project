// Generates specs/events/*.schema.json (JSON Schema draft 2020-12) from the payload table in
// docs/architecture/00-shared-contract.md section 7. One file per event type + envelope.schema.json.
// Re-run after changing a payload:  node scripts/gen-event-schemas.mjs   then   node scripts/validate-events.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'specs/events');
fs.mkdirSync(OUT, { recursive: true });

const uuid = { type: 'string', format: 'uuid', pattern: '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' };
const str = { type: 'string' };
const ts = { type: 'string', format: 'date-time' };
const money = { type: 'number', minimum: 0 };
const int1 = { type: 'integer', minimum: 1 };
const arr = (items, min = 0) => ({ type: 'array', minItems: min, items });
const obj = (props, required = Object.keys(props)) => ({ type: 'object', properties: props, required });
const email = { type: 'string', minLength: 3 };
const addr = obj({ line1: str, city: str, postalCode: str, country: str }, ['line1', 'city', 'postalCode', 'country']);

const payloads = {
  OrderCreated: { producer: 'order-service', topic: 'oms.order.events.v1', agg: 'Order', p: obj({ orderId: uuid, customerId: uuid, customerEmail: email, totalAmount: money, currency: str, items: arr(obj({ productId: uuid, sku: str, quantity: int1, unitPrice: money }), 1), shippingCity: str }) },
  OrderConfirmed: { producer: 'order-service', topic: 'oms.order.events.v1', agg: 'Order', p: obj({ orderId: uuid, customerId: uuid, customerEmail: email, totalAmount: money }) },
  OrderCancelled: { producer: 'order-service', topic: 'oms.order.events.v1', agg: 'Order', p: obj({ orderId: uuid, customerId: uuid, customerEmail: email, reason: str }) },
  OrderRejected: { producer: 'order-service', topic: 'oms.order.events.v1', agg: 'Order', p: obj({ orderId: uuid, customerId: uuid, customerEmail: email, reason: str }) },
  OrderFulfilled: { producer: 'order-service', topic: 'oms.order.events.v1', agg: 'Order', p: obj({ orderId: uuid, customerId: uuid, customerEmail: email, trackingNumber: str }) },
  OrderFailed: { producer: 'order-service', topic: 'oms.order.events.v1', agg: 'Order', p: obj({ orderId: uuid, customerId: uuid, customerEmail: email, reason: str }) },
  ReserveInventory: { producer: 'order-service', topic: 'oms.inventory.commands.v1', agg: 'Order', p: obj({ orderId: uuid, items: arr(obj({ productId: uuid, quantity: int1 }), 1), reservationTtlSeconds: { type: 'integer', minimum: 1 } }) },
  ReleaseInventory: { producer: 'order-service', topic: 'oms.inventory.commands.v1', agg: 'Order', p: obj({ orderId: uuid, reason: { type: 'string', enum: ['CANCELLATION', 'FULFILLMENT_FAILURE', 'EXPIRED'] } }) },
  InventoryReserved: { producer: 'inventory-service', topic: 'oms.inventory.events.v1', agg: 'Order', p: obj({ orderId: uuid, reservationId: uuid, items: arr(obj({ productId: uuid, quantity: int1 }), 1), expiresAt: ts }, ['orderId', 'reservationId', 'items']) },
  InventoryReservationFailed: { producer: 'inventory-service', topic: 'oms.inventory.events.v1', agg: 'Order', p: obj({ orderId: uuid, reason: { type: 'string', enum: ['INSUFFICIENT_STOCK', 'UNKNOWN_PRODUCT', 'PRODUCT_INACTIVE'] }, shortages: arr(obj({ productId: uuid, requested: { type: 'integer', minimum: 0 }, available: { type: 'integer', minimum: 0 } })) }) },
  InventoryReleased: { producer: 'inventory-service', topic: 'oms.inventory.events.v1', agg: 'Order', p: obj({ orderId: uuid, reservationId: uuid, reason: { type: 'string', enum: ['CANCELLATION', 'FULFILLMENT_FAILURE', 'EXPIRED'] } }) },
  StartFulfillment: { producer: 'order-service', topic: 'oms.fulfillment.commands.v1', agg: 'Order', p: obj({ orderId: uuid, shippingAddress: addr, items: arr(obj({ productId: uuid, sku: str, quantity: int1 }), 1) }) },
  FulfillmentStarted: { producer: 'fulfillment-service', topic: 'oms.fulfillment.events.v1', agg: 'Order', p: obj({ orderId: uuid, fulfillmentId: uuid }) },
  FulfillmentCompleted: { producer: 'fulfillment-service', topic: 'oms.fulfillment.events.v1', agg: 'Order', p: obj({ orderId: uuid, fulfillmentId: uuid, trackingNumber: str }) },
  FulfillmentFailed: { producer: 'fulfillment-service', topic: 'oms.fulfillment.events.v1', agg: 'Order', p: obj({ orderId: uuid, fulfillmentId: uuid, reason: str, attempts: { type: 'integer', minimum: 0 } }) },
};

const write = (name, schema) => fs.writeFileSync(path.join(OUT, `${name}.schema.json`), JSON.stringify(schema, null, 2) + '\n');

write('envelope', {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://oms.example/schemas/events/envelope.schema.json',
  title: 'EventEnvelope',
  description: 'Common envelope of every OMS Kafka record value (contract section 7). Consumers are tolerant readers: unknown properties are allowed.',
  type: 'object',
  required: ['eventId', 'eventType', 'schemaVersion', 'occurredAt', 'correlationId', 'aggregateType', 'aggregateId', 'producer', 'payload'],
  properties: {
    eventId: uuid, eventType: { type: 'string', enum: Object.keys(payloads) }, schemaVersion: { type: 'integer', minimum: 1 }, occurredAt: ts,
    correlationId: { type: ['string', 'null'] }, causationId: { type: ['string', 'null'] }, aggregateType: str, aggregateId: str,
    producer: { type: 'string', enum: ['order-service', 'inventory-service', 'fulfillment-service', 'notification-service'] }, payload: { type: 'object' },
  },
});
for (const [name, { producer, topic, agg, p }] of Object.entries(payloads)) {
  write(name, {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: `https://oms.example/schemas/events/${name}.schema.json`,
    title: name,
    description: `Topic ${topic}, produced by ${producer}. schemaVersion 1. Additive changes only (new optional payload fields).`,
    type: 'object',
    required: ['eventId', 'eventType', 'schemaVersion', 'occurredAt', 'correlationId', 'aggregateType', 'aggregateId', 'producer', 'payload'],
    properties: {
      eventId: uuid, eventType: { const: name }, schemaVersion: { const: 1 }, occurredAt: ts, correlationId: { type: ['string', 'null'] }, causationId: { type: ['string', 'null'] },
      aggregateType: { const: agg }, aggregateId: str, producer: { const: producer }, payload: p,
    },
  });
}
console.log(`wrote ${Object.keys(payloads).length} event schemas + envelope to specs/events`);
