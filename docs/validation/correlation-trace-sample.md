# Correlation trace sample (real logs, one order)

Captured on 2026-10-18 from a stack started with `scripts/run-local.sh --no-build --json-logs --ui` (profile `json-logs`: one JSON object per line). One order was created through the gateway with a client-chosen `X-Correlation-Id`; the lines below are every log line of all five services that carry that correlation id (extracted with `node scripts/correlation-trace.mjs <correlationId> <orderId>`, which also masks any e-mail address; the application already masks recipients as `a***@domain`, no raw e-mail address appears). Client status-polling requests (`GET /orders/{id}`) are separate requests with their own correlation ids and are not listed.

* correlationId: `85f14f49-ed9b-4c50-9b9c-8125f14e4c88` (echoed by the gateway in the response header `X-Correlation-Id`: `85f14f49-ed9b-4c50-9b9c-8125f14e4c88`)
* orderId: `cd6157a9-5cae-47e8-ac55-2be2a73227b9`, final status `FULFILLED`
* Services in the trace: api-gateway, order-service, inventory-service, fulfillment-service, notification-service (all five).

## Timeline

| Time (+05:30) | Service | Level | traceId (8) | Message |
|---|---|---|---|---|
| 21:01:57.152 | order-service | INFO | 454ba126 | Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 created with 1 line(s) (actor customer1) |
| 21:01:57.159 | api-gateway | INFO | - | POST /api/v1/orders -> 202 in 33ms route=order-service signal=onComplete |
| 21:01:57.198 | order-service | INFO | - | Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 CREATED -> VALIDATED (trigger VALIDATION_PASSED, actor saga) |
| 21:01:57.596 | notification-service | INFO | a316ead5 | Notification 0914c34d-d8ae-424c-907f-c03d7b153285 created: template=ORDER_CREATED order=cd6157a9-5cae-47e8-ac55-2be2a73227b9 to=p***@acme.example.test |
| 21:01:57.646 | inventory-service | INFO | d46d2f3a | Reserved stock: orderId=cd6157a9-5cae-47e8-ac55-2be2a73227b9 reservationId=8f3cd251-814a-4ca1-9a0b-fe20b7e104a4 lines=1 strategy=atomic |
| 21:01:57.777 | notification-service | INFO | 08687827 | Simulated EMAIL delivery OK: notification=0914c34d-d8ae-424c-907f-c03d7b153285 template=ORDER_CREATED to=p***@acme.example.test attempt=1 |
| 21:01:57.781 | notification-service | INFO | 08687827 | Notification 0914c34d-d8ae-424c-907f-c03d7b153285 SENT after 1 attempt(s) |
| 21:01:57.813 | order-service | INFO | fcb33244 | Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 VALIDATED -> INVENTORY_RESERVED (trigger RESERVATION_SUCCEEDED, actor saga) |
| 21:01:57.818 | order-service | INFO | fcb33244 | Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 INVENTORY_RESERVED -> CONFIRMED (trigger CONFIRM, actor saga) |
| 21:01:58.183 | notification-service | INFO | 07f5147f | Notification d2e52ade-034c-4ed7-b3c8-c36e4230100d created: template=ORDER_CONFIRMED order=cd6157a9-5cae-47e8-ac55-2be2a73227b9 to=p***@acme.example.test |
| 21:01:58.216 | fulfillment-service | INFO | 94123e8b | Fulfillment be7c7e8c-6668-4c2c-9d43-a247e7c29f18 accepted for order cd6157a9-5cae-47e8-ac55-2be2a73227b9 (status PENDING) |
| 21:01:58.316 | notification-service | INFO | e29057dd | Simulated EMAIL delivery OK: notification=d2e52ade-034c-4ed7-b3c8-c36e4230100d template=ORDER_CONFIRMED to=p***@acme.example.test attempt=1 |
| 21:01:58.318 | notification-service | INFO | e29057dd | Notification d2e52ade-034c-4ed7-b3c8-c36e4230100d SENT after 1 attempt(s) |
| 21:01:58.470 | notification-service | INFO | e6fc48d3 | Notification 5c1d4613-933c-4989-8a6f-2c48de734eeb created: template=FULFILLMENT_STARTED order=cd6157a9-5cae-47e8-ac55-2be2a73227b9 to=p***@acme.example.test |
| 21:01:58.586 | notification-service | INFO | e4da5624 | Simulated EMAIL delivery OK: notification=5c1d4613-933c-4989-8a6f-2c48de734eeb template=FULFILLMENT_STARTED to=p***@acme.example.test attempt=1 |
| 21:01:58.588 | notification-service | INFO | e4da5624 | Notification 5c1d4613-933c-4989-8a6f-2c48de734eeb SENT after 1 attempt(s) |
| 21:01:59.926 | fulfillment-service | INFO | 100c3f35 | Fulfillment be7c7e8c-6668-4c2c-9d43-a247e7c29f18 of order cd6157a9-5cae-47e8-ac55-2be2a73227b9 is now PICKING |
| 21:02:01.629 | fulfillment-service | INFO | bea9c602 | Fulfillment be7c7e8c-6668-4c2c-9d43-a247e7c29f18 of order cd6157a9-5cae-47e8-ac55-2be2a73227b9 is now PACKED |
| 21:02:03.198 | fulfillment-service | INFO | 909769f0 | Fulfillment be7c7e8c-6668-4c2c-9d43-a247e7c29f18 of order cd6157a9-5cae-47e8-ac55-2be2a73227b9 is now SHIPPED |
| 21:02:04.869 | fulfillment-service | INFO | 56666f34 | Fulfillment be7c7e8c-6668-4c2c-9d43-a247e7c29f18 of order cd6157a9-5cae-47e8-ac55-2be2a73227b9 COMPLETED (tracking OMS-87327C096CA8) |
| 21:02:05.158 | notification-service | INFO | 6e2c8f34 | Notification 7f066e25-8186-4c36-95f0-6f1ab1fed74a created: template=FULFILLMENT_COMPLETED order=cd6157a9-5cae-47e8-ac55-2be2a73227b9 to=p***@acme.example.test |
| 21:02:05.168 | order-service | INFO | 344d99b0 | Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 CONFIRMED -> FULFILLED (trigger FULFILLMENT_COMPLETED, actor saga) |
| 21:02:05.391 | notification-service | INFO | 38733897 | Simulated EMAIL delivery OK: notification=7f066e25-8186-4c36-95f0-6f1ab1fed74a template=FULFILLMENT_COMPLETED to=p***@acme.example.test attempt=1 |
| 21:02:05.395 | notification-service | INFO | 38733897 | Notification 7f066e25-8186-4c36-95f0-6f1ab1fed74a SENT after 1 attempt(s) |
| 21:02:05.526 | notification-service | INFO | 516773fd | Notification da8f14f7-8c15-45a7-9a1e-36ec5a19c909 created: template=ORDER_FULFILLED order=cd6157a9-5cae-47e8-ac55-2be2a73227b9 to=p***@acme.example.test |
| 21:02:05.653 | notification-service | INFO | 5b115c5a | Simulated EMAIL delivery OK: notification=da8f14f7-8c15-45a7-9a1e-36ec5a19c909 template=ORDER_FULFILLED to=p***@acme.example.test attempt=1 |
| 21:02:05.657 | notification-service | INFO | 5b115c5a | Notification da8f14f7-8c15-45a7-9a1e-36ec5a19c909 SENT after 1 attempt(s) |

## What this shows (and what it does not)

* The correlation id travels HTTP (gateway -> order-service) and then Kafka headers (outbox -> inventory, fulfillment, notification -> back to order-service) and is on every line, so one id reconstructs the whole saga across five processes.
* `traceId`/`spanId` are present only on lines written inside a Kafka listener/scheduler observation; the value **changes at every Kafka hop** (each consumer starts its own trace) and the gateway lines have none. In this local run tracing export is off (`management.tracing.enabled=false`) and no collector exists, so end-to-end distributed traces (one traceId across services) were NOT demonstrated; the correlationId is the join key. Verifying W3C `traceparent` propagation needs the OTel collector/Tempo (not run).
* order-service and fulfillment-service logged nothing about a successful order before this integration pass (their lifecycle lines were added as a fix, see integration-defects.md D-08), so earlier traces showed only gateway, inventory and notification.

## Raw JSON lines (first 3, unabridged field set)

```json
{"timestamp":"2026-10-18T21:01:57.1523688+05:30","level":"INFO","service":"order-service","correlationId":"85f14f49-ed9b-4c50-9b9c-8125f14e4c88","traceId":"454ba126ab05babe2399ecf872c9389f","spanId":"8d4c54cbfe4ce317","logger":"com.enterprise.oms.order.service.OrderTransitions","message":"Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 created with 1 line(s) (actor customer1)"}
{"timestamp":"2026-10-18T21:01:57.1598992+05:30","level":"INFO","service":"api-gateway","correlationId":"85f14f49-ed9b-4c50-9b9c-8125f14e4c88","traceId":null,"spanId":null,"logger":"oms.gateway.access","message":"POST /api/v1/orders -> 202 in 33ms route=order-service signal=onComplete"}
{"timestamp":"2026-10-18T21:01:57.1988991+05:30","level":"INFO","service":"order-service","correlationId":"85f14f49-ed9b-4c50-9b9c-8125f14e4c88","traceId":null,"spanId":null,"logger":"com.enterprise.oms.order.service.OrderTransitions","message":"Order cd6157a9-5cae-47e8-ac55-2be2a73227b9 CREATED -> VALIDATED (trigger VALIDATION_PASSED, actor saga)"}
```
