# REST API Design: Resources, Errors, Idempotency, Pagination and Authorization

The OMS exposes a versioned REST API behind a gateway (`/api/v1/...`). The normative description is `docs/architecture/00-shared-contract.md` section 5; this chapter explains the design decisions, shows the real code that implements them and lists the interview questions they answer. Everything is synthetic data.

## Big picture and kid-friendly analogy

Picture a big library with a friendly front desk.

* **Resources and URLs** are the shelf labels: `/orders/7f9c...` is "the order with this number". You do not ask for "getOrder" or "doOrderThing"; you point at a thing and say what you want to do to it (read it, add one, replace it, remove it). Those are the **verbs** (`GET`, `POST`, `PUT`, `DELETE`).
* **Status codes** are traffic lights. Green 2xx: "done". Yellow 3xx: "go somewhere else". Red 4xx: "you did something wrong". Red 5xx: "we did something wrong".
* **202 Accepted** is the bakery giving you a ticket number: "we have your cake order, come back and show the ticket". You do not stand at the counter until the cake is baked.
* **Idempotency-Key** is the coat-check ticket. If you hand in your coat twice because the clerk looked away, the ticket number is the same and you still get exactly one coat hook.
* **problem+json** is a standard "sorry" note that always has the same boxes: what happened, a short code, and where to look.
* **Pagination** is reading a book a few pages at a time instead of carrying the whole shelf.
* **404 instead of 403** for somebody else's order is a hotel that answers "there is no guest by that name" whether the guest does not exist or simply does not want to be found.
* **Rate limiting** is the line at the ice-cream stand: everybody gets served, but nobody gets to take 100 cones.

| Analogy | REST concept |
|---|---|
| Shelf labels | Resource-oriented URLs, nouns not verbs |
| Traffic lights | HTTP status code families |
| Bakery ticket | `202 Accepted` + `Location` + polling |
| Coat-check ticket | `Idempotency-Key` header |
| Standard sorry note | RFC 7807 / RFC 9457 `application/problem+json` |
| Reading a few pages | Page, size and sort parameters |
| Hotel "no such guest" | 404 to prevent object enumeration (BOLA defence) |
| Ice-cream queue | Token-bucket rate limit, `429` with `Retry-After` |

## Beginner tier

### Example 1: resources, verbs and the right status code

The orders API (`services/order-service/src/main/java/com/enterprise/oms/order/api/OrderController.java`) is a small, regular surface:

| Method and path | Meaning | Success status |
|---|---|---|
| `POST /api/v1/orders` | Create an order (asynchronous) | `202 Accepted` + `Location` |
| `GET /api/v1/orders` | Search with filters and paging | `200` |
| `GET /api/v1/orders/{id}` | Read one | `200` (or `404`) |
| `GET /api/v1/orders/{id}/history` | Status timeline | `200` |
| `POST /api/v1/orders/{id}/cancel` | Run the cancel command | `200` (or `409`) |

Creation of a synchronous resource returns **201** with a `Location` header. The product catalogue does that (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/web/ProductController.java`):

```java
@PostMapping
@PreAuthorize("hasRole('ADMIN')")
@Operation(summary = "Create a product (ADMIN)")
public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest body) {
    ProductView v = products.create(body.sku(), body.name(), body.description(), body.category(), body.price(),
            body.initialStock() == null ? 0 : body.initialStock());
    return ResponseEntity.created(URI.create("/api/v1/products/" + v.id())).body(ProductResponse.of(v, true));
}
```

Design choices worth defending: `DELETE /products/{id}` **deactivates** and is idempotent (it returns `200` with the product rather than `204`, so the client sees the resulting state); the cancel is a `POST` to a **sub-resource command** (`/cancel`) because it is a state-machine transition with side effects and business rules (allowed only from `CREATED`, `VALIDATED`, `INVENTORY_RESERVED`, `CONFIRMED`, otherwise `409 INVALID_STATE_TRANSITION`), not a simple field update. The alternative, `PATCH /orders/{id}` with `{"status":"CANCELLED"}`, would let clients believe they may set any status.

Method semantics to recite: `GET`, `HEAD`, `OPTIONS` are safe; `GET`, `PUT`, `DELETE` are idempotent; `POST` is neither, which is exactly why `POST /orders` needs an application-level idempotency key.

### Example 2: one error format, RFC 7807 `problem+json`

Every failure, from validation to rate limiting, is the same JSON shape with a stable machine-readable `code`. `ProblemFactory` (`services/platform-common/src/main/java/com/enterprise/oms/common/error/ProblemFactory.java`) builds it on Spring's `ProblemDetail`:

```java
public static ProblemDetail create(ErrorCode code, HttpStatusCode status, String detail, String instance,
                                   List<FieldError> errors) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create(code.typeUri()));
    pd.setTitle(code.title());
    setInstance(pd, instance);
    pd.setProperty("code", code.name());
    pd.setProperty("correlationId", CorrelationIds.current());
    pd.setProperty("timestamp", Instant.now().toString());
    pd.setProperty("errors", errors == null ? List.of() : errors);
    return pd;
}
```

A request with a zero quantity and a blank city produces (shape defined in the contract, built by `GlobalExceptionHandler.handleMethodArgumentNotValid`; the field names are illustrative):

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://oms.example/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/v1/orders",
  "code": "VALIDATION_FAILED",
  "correlationId": "b1a2c3d4-0000-4000-8000-00000000a004",
  "timestamp": "2026-10-18T09:18:00Z",
  "errors": [
    { "field": "items[0].quantity", "message": "must be between 1 and 1000" },
    { "field": "shippingAddress.city", "message": "must not be blank" }
  ]
}
```

Why it is designed this way: the **`code`** is the contract (clients branch on `INSUFFICIENT_STOCK`, not on English text); the `type` URI is derived mechanically (`ErrorCode.typeUri`: `https://oms.example/problems/<code-kebab>`); `correlationId` lets support find the log lines; `errors` is always present, possibly empty, so clients need no null checks. `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so framework-raised errors (405, 415, malformed JSON) get the same shape, and the catch-all handler returns a generic `INTERNAL_ERROR` without stack traces or SQL (`server.error.include-*` are also set to `never` in `oms-defaults.properties`). RFC 7807 has since been obsoleted by RFC 9457; the media type and members are the same, so the design is unaffected.

### Example 3: pagination with hard limits

Every list endpoint takes `page` (0-based), `size` and repeated `sort=field,asc|desc`. The parsing is centralised in `PageRequestFactory` (`services/platform-common/src/main/java/com/enterprise/oms/common/web/PageRequestFactory.java`):

```java
if (p < 0) {
    errors.add(new FieldError("page", "must be >= 0"));
}
if (s < 1 || s > maxSize) {
    errors.add(new FieldError("size", "must be between 1 and " + maxSize));
}
```

and each controller passes a **whitelist** of sortable fields, e.g. in `OrderController`:

```java
static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "status", "totalAmount");
static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
```

```http
GET /api/v1/orders?status=CONFIRMED&page=0&size=20&sort=createdAt,desc
```

```json
{ "content": [ { "id": "...", "status": "CONFIRMED" } ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```

Invalid values are a `400 VALIDATION_FAILED`, never silently clamped: a client asking for `size=10000` should learn about the limit rather than get 100 rows and wonder. The sort whitelist matters for security and performance: sorting on arbitrary client-supplied property names can expose columns or force unindexed sorts. This is the cure for the "unbounded pagination" review scenario in `docs/code-review/06-unbounded-pagination-n-plus-one.md`.

## Intermediate tier

### Example 1: `202 Accepted`, `Location` and an asynchronous workflow

Creating an order starts a saga that spans several services, so the API cannot honestly answer "done". `OrderController.create` returns **202** immediately with the order in status `CREATED`:

```java
OrderCommandService.CreateResult result = commands.create(request, idempotencyKey, user);
ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
        .location(URI.create("/api/v1/orders/" + result.order().id()));
if (result.replay()) {
    response.header("Idempotent-Replay", "true");
}
return response.body(result.order());
```

```http
POST /api/v1/orders HTTP/1.1
Authorization: Bearer <JWT>
Idempotency-Key: ord-2026-10-18-0001

HTTP/1.1 202 Accepted
Location: /api/v1/orders/7f9c1e2a-5b3d-4c8e-9a10-0d2f4b6c8e01
```

The client then polls `GET /api/v1/orders/{id}` until a terminal status. The Angular helper `pollUntil` in `frontend/oms-ui/src/app/core/state/polling.ts` implements polling with growing delays (`backoffDelay`, factor 1.5 up to a maximum) and stops when the component is destroyed. Business failures also surface asynchronously: an order for out-of-stock goods is `202` first and `REJECTED` with a `statusReason` seconds later, not a synchronous 409. Per the contract the first response may show `totalAmount = 0.00` and null `sku/name/unitPrice` on lines until validation has priced the order, so UI and API consumers must treat early snapshots as incomplete. Interview talking points: 202 describes the *request*, not the outcome; return a `Location` the client can poll; consider a dedicated status resource or `Retry-After`; offer webhooks or server-sent events if polling becomes costly.

### Example 2: idempotency keys done properly

`Idempotency-Key` is mandatory on `POST /orders`. Format validation (`services/platform-common/src/main/java/com/enterprise/oms/common/web/IdempotencyKeys.java`):

```java
private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
...
if (headerValue == null || headerValue.isBlank()) {
    throw new IdempotencyKeyRequiredException();
}
```

Absent key: `400 IDEMPOTENCY_KEY_REQUIRED`; malformed key: `400 VALIDATION_FAILED`. The logic is in `OrderCommandService.doCreate` (`services/order-service/src/main/java/com/enterprise/oms/order/service/OrderCommandService.java`): look the key up; if found, compare the request fingerprint and replay the stored response; otherwise insert the key row **first** (unique on scope and key) so a concurrent duplicate blocks on the unique index; then create the order, saga and history; finally store the response snapshot on the key row, all in one transaction:

```java
Optional<IdempotencyKeyRecord> existing = idempotencyKeys.findByScopeAndKey(scope, key);
if (existing.isPresent()) {
    return replayOf(existing.get(), hash);
}
...
IdempotencyKeyRecord record = idempotencyKeys.saveAndFlush(new IdempotencyKeyRecord(scope, key, hash, now));
```

Behaviours, all backed by the `idempotency_keys` table (`V11__saga_idempotency.sql`, `UNIQUE (scope, idem_key)`):

| Situation | Result |
|---|---|
| Same key, same body | `202` with the **original** body and header `Idempotent-Replay: true` |
| Same key, different body | `422 IDEMPOTENCY_KEY_REUSED` |
| Same key, first request still in flight | `409 CONFLICT` ("original request is still being processed") |
| Key reused after the 24 h retention | Treated as new |

The fingerprint is a SHA-256 of a **canonical** form (`CreateOrderRequest.canonical()` sorts lines by product id and trims the address), so whitespace, JSON property order and line order do not change it. The scope is the `customerId`, so two customers can use the same key string. Note the replay returns a snapshot, not live state: use `GET` to read the current status. A request that fails validation or authorisation does not consume its key. Interview edge: this pattern matches the IETF "Idempotency-Key header" draft; the key store must commit atomically with the effect, which is why it shares the transaction with the order insert.

### Example 3: object-level authorisation (BOLA) and 404 versus 403

OWASP API1 (Broken Object Level Authorization) is the most common real-world API flaw: a role check says "customers may read orders", but not "this customer may read this order". The OMS enforces three layers. URL rules reject unauthorised roles before the body is even bound (`services/order-service/src/main/java/com/enterprise/oms/order/config/OrderSecurityConfig.java`), `@PreAuthorize` on controller methods repeats it, and resource-level checks in the service decide ownership. The read side (`OrderQueryService`):

```java
private CustomerOrder visibleOrder(UUID id, CurrentUser user) {
    return orders.findWithItemsById(id).filter(o -> user.canAccessCustomer(o.getCustomerId()))
            .orElseThrow(() -> new NotFoundException("Order", id));
}
```

with `CurrentUser.canAccessCustomer` in platform-common:

```java
public boolean canAccessCustomer(UUID ownerCustomerId) {
    return isStaff() || (isCustomer() && customerId != null && customerId.equals(ownerCustomerId));
}
```

Another customer's order is **404**, identical to a missing one, so an attacker cannot enumerate valid ids. For list endpoints the filter is forced rather than validated (`OrderQueryService.search`): a customer who passes `customerId=<someone else>` silently gets their own orders. For writes the identity comes from the **token**, never trusted from the body: `OrderCommandService.authorizeCustomer` throws `403` when the body `customerId` differs from the token's `customerId` claim. The 404-vs-403 split is deliberate and documented: 403 where the caller supplied the identifier themselves (their own claim does not match the body), 404 where existence itself is the secret. `docs/code-review/02-bola-customerId-from-body.md` is the teaching scenario (a flawed pull request, not live code) and ends with regression tests worth copying.

## Advanced tier

### Example 1: versioning, OpenAPI and contract governance

The version is in the URL path (`/api/v1`), and within v1 only additive changes are allowed (ADR-0007, `docs/architecture/api-versioning.md`). The practical rules to quote: adding an optional request field, a response field, an endpoint, a new error `code` or loosening validation is non-breaking; removing or renaming a field, changing a type, scale or unit, tightening validation, changing page-size defaults or changing 403 to 404 is breaking and needs `/api/v2`. Deprecation is communicated with `Deprecation` and `Sunset` headers with a minimum notice period, and the gateway routes by path prefix so v2 can be added as a new route without touching v1.

Documentation is generated, not hand-written: controllers carry springdoc annotations (`@Operation`, `@ApiResponse`, `@Tag`) and each service serves `/v3/api-docs` (not routed through the gateway, so the public surface does not widen). `scripts/export-openapi.mjs` writes `specs/openapi/<service>.json`, and the policy is an OpenAPI-diff step in CI that fails on breaking changes. Honest status: only `specs/openapi/inventory-service.json` is committed today, and the CI diff step is described as planned in `docs/architecture/api-versioning.md`; the contract document is the reference where an export is missing. Interview contrast: media-type versioning is "purer" REST but invisible in logs and awkward in browsers; query-parameter versioning pollutes cache keys.

### Example 2: optimistic concurrency, `ETag` and why there is no HATEOAS

Lost updates are prevented with a version precondition. `PUT /api/v1/products/{id}` accepts an optional `version`; `ProductService.update` compares it with the row version:

```java
Product p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
if (expectedVersion != null && !expectedVersion.equals(p.getVersion())) {
    throw new ConflictException("Product was modified by someone else (current version " + p.getVersion() + ")");
}
```

A mismatch is `409 CONFLICT`. The HTTP-native version of the same idea is a strong `ETag` on `GET` and `If-Match` on `PUT`, answering `412 Precondition Failed` (or `428 Precondition Required` when the header is missing). This API chose a body field because every order and product JSON already exposes `version`, which keeps the UI simple; mention both and say why. Optimistic concurrency in the persistence layer (`@Version`) additionally maps to `409` through `GlobalExceptionHandler.handleOptimisticLock`.

**HATEOAS** (hypermedia controls: responses carry links such as `cancel` that appear only when the transition is legal) would suit the order state machine well, because the allowed actions depend on status. This API stays at Richardson maturity level 2: resources plus verbs plus status codes. The reasons are pragmatic: the only first-party client is an Angular app that already knows the cancel rule, the contract and OpenAPI document the transitions, and hypermedia tooling and client support are thin. If many third-party clients appeared, adding an `allowedActions` array or link relations would be a non-breaking addition to the response. The honest wart worth mentioning: `GET /orders/{id}/history` returns a bare JSON array instead of the pagination envelope; a bare array cannot grow additional members without a breaking change.

### Example 3: layered defence at the edge and in the service

REST design is also about what happens before a controller runs. The platform stacks these controls:

* **Size and shape**: bodies over 64 KB are rejected before parsing with `413 PAYLOAD_TOO_LARGE` (servlet `RequestSizeLimitFilter` in services, `RequestSizeLimitWebFilter` in the gateway); `spring.servlet.multipart.enabled=false`; Jackson binding is strict where it matters (`accept-float-as-int: false` in inventory, so `{"delta":1.5}` is a 400 and not a silent truncation to 1).
* **Path hygiene** at the gateway (`EdgeFilters.PathGuardWebFilter`): rejects `..`, encoded separators (`%2f`), matrix parameters (`;`) and double encoding with 400, and answers 404 for anything under `/api/v1/internal`, which is never routed.
* **Rate limiting** twice: at the edge per JWT subject (capacity 100, refill 50 per second) and on the expensive `POST /orders` per caller (capacity 20, 5 per second; `OMS_ORDER_RATE_*`). The service-level handler adds the `Retry-After` header (`RateLimitAdvice`):

```java
@ExceptionHandler(OrderRateLimitedException.class)
public ResponseEntity<ProblemDetail> handle(OrderRateLimitedException ex, HttpServletRequest request) {
    ProblemDetail pd = ProblemFactory.create(ErrorCode.RATE_LIMITED, ex.getMessage(), request.getRequestURI(), null);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
}
```

* **Authorisation order**: role rules run before request binding, so an unauthorised caller gets 403 whatever the body looks like (403 before 400). That prevents the validation messages from becoming an oracle for what exists.
* **Upstream failures**: when a service is down the gateway returns `503 UPSTREAM_UNAVAILABLE` with `Retry-After: 5`; upstream 4xx and 5xx responses pass through unchanged.
* **Headers**: the gateway strips client-supplied identity-style headers (`X-User-*`, `X-Roles`, `X-Forwarded-*`) and adds security headers; `X-Correlation-Id` is accepted if well formed (`[A-Za-z0-9._-]{1,64}`), generated otherwise, forwarded and echoed on every response.

A good closing point for interviews: none of these replaces the others. Gateway checks are coarse and "defence in depth only"; each service still validates the JWT, enforces ownership and applies its own limits.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Resource design and verbs | `services/order-service/src/main/java/com/enterprise/oms/order/api/OrderController.java`, `services/inventory-service/src/main/java/com/enterprise/oms/inventory/web/ProductController.java`, contract section 5 | Commands as sub-resources (`/cancel`) |
| problem+json | `services/platform-common/src/main/java/com/enterprise/oms/common/error/ProblemFactory.java`, `GlobalExceptionHandler.java`, `ErrorCode.java` | Stable `code`, no stack traces |
| Pagination | `services/platform-common/src/main/java/com/enterprise/oms/common/web/PageRequestFactory.java`, `PageResponse.java` | 400 on invalid input, sort whitelist |
| 202 + polling | `OrderController.create`, `frontend/oms-ui/src/app/core/state/polling.ts`, `docs/architecture/saga-and-consistency.md` | UI polls with backoff |
| Idempotency | `services/platform-common/src/main/java/com/enterprise/oms/common/web/IdempotencyKeys.java`, `OrderCommandService.java`, `V11__saga_idempotency.sql` | Scope = customerId, 24 h retention |
| Object-level authorisation | `OrderQueryService.java`, `CurrentUser.java`, `OrderSecurityConfig.java` | 404 for foreign orders |
| Rate limiting | `services/order-service/src/main/java/com/enterprise/oms/order/ratelimit/*`, `gateway/src/main/java/com/enterprise/oms/gateway/ratelimit/*` | In-memory default, Redis optional |
| Versioning and OpenAPI | `docs/adr/0007-api-versioning.md`, `docs/architecture/api-versioning.md`, `specs/openapi/inventory-service.json` | Only one export committed |
| Optimistic concurrency | `services/inventory-service/src/main/java/com/enterprise/oms/inventory/service/ProductService.java`, `Stock.java` | Body `version` field, no ETag |
| Edge hardening | `gateway/src/main/java/com/enterprise/oms/gateway/filter/EdgeFilters.java`, `RequestSizeLimitWebFilter.java` | Path guard, header stripping |

### Demonstration-only vs production-grade

* **Production-grade**: consistent error contract, strict validation and limits, forced filters and ownership checks, `Idempotency-Key` with atomic storage, additive-only evolution policy, correlation ids on every response, no information leakage in errors.
* **Demonstration-only / gaps**: the JWT is HS256 with a shared demo secret and a dev-only token endpoint; `docs/api/examples.md` is now regenerated from live responses by `scripts/capture-api-examples.mjs` (38 examples, tokens redacted); all four service OpenAPI exports and a generated gateway file are committed under `specs/openapi`, but the CI breaking-change diff is not wired into `ci.yml`; there is no HATEOAS, no ETag/`If-Match`, no cursor pagination (offset paging with `totalElements` costs a count query and drifts under concurrent inserts) and no public rate-limit policy headers beyond `Retry-After` and `X-RateLimit-Remaining` at the gateway.
* **Verification status**: MockMvc and gateway tests cover the status codes, headers and authorisation matrix (the order-service README reports 433 unit and H2 tests); real PostgreSQL concurrency behaviour of the idempotency unique index is verified only through Testcontainers tests that are skipped when Docker is absent, which is the case on the dev machine.

## Common mistakes and gotchas

* Verbs in URLs (`/createOrder`) and returning `200` with an error body.
* `201` versus `202` confusion: `202` must point to something the client can poll; `201` means the resource exists now.
* Using `403` for "not yours" and leaking existence; or using `404` for everything and making debugging impossible. Decide per case and document it.
* Taking `customerId` (or any owner id) from the request body instead of the token.
* Treating `POST` as retry-safe without an idempotency key; storing the key in a different transaction from the effect; replaying live state instead of the original response; not hashing the body (so "same key, different body" slips through).
* Unbounded `size`, sorting on arbitrary properties, or paging a collection fetch join in memory.
* Inconsistent envelopes (a bare array here, a page object there) that cannot evolve.
* Parsing error text on the client instead of a stable `code`.
* Leaking stack traces, SQL or internal class names; echoing `detail` from authentication failures.
* Breaking changes disguised as fixes: tightening validation, changing enum meaning, changing 403 to 404.
* Rate limiting without `Retry-After`, or per IP behind a load balancer where everyone shares one address.
* Retrying `POST` at the gateway without idempotency support.

## Quick-recall cheat sheet

* Nouns in URLs, verbs in methods; commands that change state under rules can be sub-resources (`POST /orders/{id}/cancel`).
* 200 OK, 201 Created + `Location`, 202 Accepted (async), 204 No Content, 400 validation, 401 unauthenticated, 403 forbidden, 404 not found, 409 conflict or invalid state, 413 too large, 422 semantically invalid (idempotency key reuse), 429 + `Retry-After`, 503 + `Retry-After`.
* Error body: RFC 7807/9457 `application/problem+json` with `type, title, status, detail, instance` plus extensions `code, correlationId, timestamp, errors[]`; clients branch on `code`.
* Paging: `page` 0-based, `size` 1 to 100, `sort=field,dir` from a whitelist, invalid means 400, add a stable tie-break sort.
* Idempotency-Key: 8 to 64 chars `[A-Za-z0-9_-]`, unique per `(scope, key)`, fingerprint the canonical body, replay the original response with `Idempotent-Replay: true`, 422 on body mismatch, 409 if in flight.
* BOLA defence: role check plus ownership check; foreign object returns 404; forced filters on lists; identity from the token.
* Versioning: URL major version, additive-only inside v1, `Deprecation`/`Sunset` headers, OpenAPI diff in CI.
* Optimistic concurrency: version in body (here) or `ETag`/`If-Match` with 412/428 (HTTP-native).
* HATEOAS = maturity level 3; this API is level 2 on purpose.
* Edge limits: 64 KB body, path guard, header stripping, per-subject rate limit after authentication.
* 202 means "accepted for processing", not "done": poll `GET` with backoff, show intermediate states honestly.
