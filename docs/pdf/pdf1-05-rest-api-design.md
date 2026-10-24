## Conventions (all services)

| Topic | Rule | Where implemented |
|---|---|---|
| Base path and versioning | `/api/v1`, additive-only changes inside v1 (ADR-0007) | Controllers, `docs/architecture/api-versioning.md` |
| Errors | RFC 7807 `application/problem+json` with `code`, `correlationId`, `errors[]`; no stack traces or SQL | `services/platform-common/src/main/java/com/enterprise/oms/common/error/GlobalExceptionHandler.java` |
| Correlation | `X-Correlation-Id` accepted or generated, echoed, placed in MDC | `common/web` filter in platform-common, gateway `EdgeFilters` |
| Idempotency | `Idempotency-Key` (8 to 64 chars) required on `POST /orders` | `services/order-service/src/main/java/com/enterprise/oms/order/service/OrderCommandService.java` |
| Pagination | `page` (0-based), `size` default 20 max 100, whitelisted `sort`; invalid values are 400, never silently clamped | platform-common paging helpers |
| Money and time | `BigDecimal` with scale 2, `USD`; ISO-8601 UTC timestamps | DTO records |
| Limits | JSON body max 64 KB (413), `POST /orders` rate limit per caller (429 + `Retry-After`) | gateway `RequestSizeLimitWebFilter`, order `ratelimit` package |

## Endpoint groups

| Service | Examples | Roles |
|---|---|---|
| order | `POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `POST /api/v1/orders/{id}/cancel`, `GET /api/v1/orders/{id}/history` | owner, OPERATOR, ADMIN |
| order admin | `GET /api/v1/admin/orders/failed`, `POST /api/v1/admin/orders/{id}/retry`, `GET /api/v1/admin/saga/{orderId}`, `POST /api/v1/admin/reconciliation/run` | OPERATOR, ADMIN |
| inventory | `GET /api/v1/products`, `PUT /api/v1/inventory/{productId}/stock`, `GET /api/v1/inventory/reservations` | any authenticated, OPERATOR, ADMIN |
| internal | `POST /api/v1/internal/products/validate` | SERVICE only, not routed by the gateway |
| fulfillment | `GET /api/v1/fulfillments`, `POST /api/v1/admin/fulfillments/{id}/retry` | OPERATOR, ADMIN |
| notification | `GET /api/v1/notifications`, `POST /api/v1/admin/notifications/{id}/retry` | customer (own), OPERATOR, ADMIN |
| operations | `GET /api/v1/ops/messaging`, `GET /api/v1/ops/audit`, gateway `GET /api/v1/ops/health` | OPERATOR, ADMIN |

The complete, authoritative table is section 5 of `docs/architecture/00-shared-contract.md`; OpenAPI exports live in `specs/openapi/`.

## Design decisions worth defending

**202 Accepted for order creation.** The order is persisted and a saga row created in one transaction, then the API answers `202` with a `Location` header. Validation, reservation and fulfillment continue asynchronously. The client polls `GET /orders/{id}`. This keeps the request fast and honest: the order is accepted, not yet fulfilled. The Angular UI uses `pollUntil` with exponential backoff (`frontend/oms-ui/src/app/core/state/polling.ts`).

**Idempotency key semantics.** The scope is the `customerId` of the request body; the fingerprint is a SHA-256 over a canonical form (customer, lines sorted by product, trimmed address), so property order, whitespace and line order do not matter. Same key and same fingerprint returns the original `202` body with header `Idempotent-Replay: true`. Same key and a different fingerprint returns `422 IDEMPOTENCY_KEY_REUSED`. A request that fails validation or authorisation does not consume its key. Keys are retained for 24 hours (`oms.idempotency.retention`) and cleaned by `IdempotencyKeyCleanupJob`.

**404 instead of 403 for other customers' orders.** Returning 404 prevents order-id enumeration. Role-based URL rules run before request binding, so an unauthorised caller receives 403 before 400. This is the BOLA defence (see the threat model and the code-review exercise 02 in `docs/code-review/`).

**Problem details.** One error shape everywhere means the UI has one `errorInterceptor` (`frontend/oms-ui/src/app/core/http/interceptors.ts`) that turns it into an `ApiError`.

**Optimistic concurrency on edits.** `PUT /products/{id}` accepts an optional `version`; a mismatch returns `409 CONFLICT`. Orders carry a `version` column used for optimistic locking.

## Example: create order and replay

```http
POST /api/v1/orders
Authorization: Bearer <JWT>
Idempotency-Key: demo-key-0001
Content-Type: application/json

{"customerId":"c0000000-0000-0000-0000-000000000001",
 "items":[{"productId":"a0000000-0000-0000-0000-000000001001","quantity":2}],
 "shippingAddress":{"line1":"1 Main St","city":"Springfield","postalCode":"12345","country":"US"}}

HTTP/1.1 202 Accepted
Location: /api/v1/orders/<orderId>
```

Replaying the same request returns the same body plus `Idempotent-Replay: true`. Captured responses from a live stack are collected in `docs/api/examples.md` when the integration run has produced them.

{{code:services/order-service/src/main/java/com/enterprise/oms/order/api/OrderController.java|@PostMapping|30|java}}

## What is not done (honest list)

* No HATEOAS links: the API is resource-oriented REST at maturity level 2; hypermedia was judged low value for a UI that already knows the routes.
* No ETag or conditional GET; versions are explicit fields instead.
* Pagination is offset-based. Keyset pagination is recommended for very deep paging and is discussed in the database chapter of the interview guide.
