# Mentoring: Security

Verified on disk: `CurrentUser` (with `canAccessCustomer`), `Roles`, `ProblemSecurityHandlers`, `JwtSecret`, `JwtTokenFactory`, `ServiceTokenProvider`, `LogMasking`, `RequestSizeLimitFilter`, `PageRequestFactory` under `services/platform-common`. Order, inventory, fulfillment and notification controllers and the gateway exist now (`OrderController`, `InventoryController`, `EdgeSecurityConfig`, ...; tests `OrderSecurityTest`, `EdgeSecurityTest`); snippets for them remain teaching sketches.

---

## 1. BOLA: trusting `customerId` from the body

**PR (sketch):**

```java
@PostMapping("/orders")
ResponseEntity<Order> create(@RequestBody CreateOrderRequest req, @RequestHeader("Idempotency-Key") String key) {
    return accepted(useCase.execute(req.customerId(), req.items(), key));
}
```

**Reviewer:** Who is `req.customerId()`? Whoever the caller says. A CUSTOMER token for customer 1 can post `customerId = c0000000-...-0002` and create orders on customer 2's account, charging their credit and spamming their notifications. This is Broken Object Level Authorization.

**Junior:** The gateway validates the JWT.

**Reviewer:** The gateway proves who you are. It does not know your business rule "CUSTOMER may create orders only for own customerId". The contract says the backend is authoritative.

**Better version:**

```java
CurrentUser user = CurrentUser.require();                       // verified
if (!user.canAccessCustomer(req.customerId())) {                // staff pass, customers only self
    throw new ForbiddenException("customerId does not match authenticated customer");
}
```

For reads the contract is stricter: another customer's order returns **404, not 403**, so IDs cannot be enumerated.

```java
Order o = orders.findById(id).orElseThrow(() -> new NotFoundException("Order", id));
if (!user.canAccessCustomer(o.customerId())) throw new NotFoundException("Order", id);  // same message as missing
```

**Junior:** Different status for POST (403) and GET (404)?

**Reviewer:** On create the caller supplied the target, so there is nothing to hide; revealing a refusal is fine. On read the existence of the object is the secret. Keep both messages free of the foreign id's details.

**Takeaway:** derive identity from the token; compare every object owner with it; same response for "missing" and "not yours".

---

## 2. Mass assignment and fields the client must not set

**PR:** `PUT /products/{id}` binds the JPA entity directly.

**Reviewer:** The entity has `onHand`, `reserved`, `version`. A crafted body edits stock without the `stock_movement` and audit row that `PUT /inventory/{productId}/stock` writes, bypasses the "result must be >= reserved" rule, and defeats optimistic locking by supplying `version`. Use request records with only the editable fields (name, description, category, price, active). Stock changes go through the one endpoint that audits them.

**Takeaway:** request DTOs are allow-lists. Never `@RequestBody` an entity.

---

## 3. Authorization lives in two places: method and resource

**Reviewer:** `@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")` answers "may this role call this endpoint". It cannot answer "may this customer see this order". You need both layers. Common failure: an operator-only list endpoint reused for customers with a `customerId` request parameter. The contract says for `GET /orders` a CUSTOMER gets a **forced** filter:

```java
// sketch
UUID filter = user.isCustomer() ? user.customerId() : params.customerId();
```

The query parameter is ignored for customers, not merely validated.

**Test (MockMvc):** customer 1 calls `GET /orders?customerId=<customer2>`; response must contain zero customer-2 orders.

---

## 4. JWT handling

**PR:** `jwtDecoder` built with `NimbusJwtDecoder.withSecretKey(...)` and no audience or issuer validation.

**Reviewer:** Claims in the contract: `iss=oms-dev-issuer`, `aud=oms-api`. A token minted by another system with the same secret (or in OIDC mode, another tenant on the same IdP) would be accepted. Validate issuer, audience and expiry. Also:

* Secret comes from `OMS_JWT_SECRET`, at least 32 bytes, and **startup must fail if absent** (the contract). No `application.yml` default, not even "dev-secret-change-me". A default secret becomes the production secret the day someone forgets an env var. `JwtSecret` in platform-common enforces this unless `allow-ephemeral-secret=true`, which is for tests only.
* Roles come from the `roles` claim, never from a request header like `X-Role`.
* `/api/v1/internal/**` requires `SERVICE` and is not routed by the gateway; keep it that way. The service token is short-lived and minted by `ServiceTokenProvider`.

**Takeaway:** fail closed on missing config; validate iss/aud/exp; internal endpoints are not reachable from the edge.

---

## 5. Logging without leaking

**PR (BEFORE):**

```java
log.info("Creating order for {} token={} body={}", req.customerEmail(), authHeader, objectMapper.writeValueAsString(req));
```

**Reviewer:** Email (PII), bearer token (credential), and the full body including the shipping address. Logs travel to Loki/ELK with broader access than the database. Contract section 10: never log tokens/passwords/emails in full; mask emails `a***@domain`.

```java
log.info("Creating order customerId={} items={} email={}", req.customerId(), req.items().size(),
        LogMasking.maskEmail(email));        // verified helper
```

Also use `LogMasking.stripNewlines` on any user-controlled string you log (log injection: a header value containing `\n` can forge a log line). Detail in `../code-review/07-pii-in-logs.md`.

---

## 6. Errors that do not help attackers

**Reviewer:** The `GlobalExceptionHandler` (verified) must never return stack traces or SQL. In review, look for `e.getMessage()` placed into `detail` for unexpected exceptions: that leaks constraint names and table names. Unexpected -> `INTERNAL_ERROR` with the `correlationId`, details go to the log.

Login failure (`POST /api/v1/auth/token`): the same 401 for unknown user and bad password; the UI must not echo `detail`.

---

## 7. Input limits are security controls

* JSON body max 64 KB (`RequestSizeLimitFilter`, verified) -> 413 `PAYLOAD_TOO_LARGE`.
* Items 1..50, quantity 1..1000, no duplicate productId.
* `page` / `size` / `sort` are validated and rejected, not clamped (`PageRequestFactory`, verified): an unbounded `size` is a cheap denial of service, and a free-form `sort` is a path to SQL/JPQL injection or leaking columns.
* Rate limit at the gateway with `Retry-After`; Redis optional, falls back to in-memory.

## Do-not-do list for this repo

- Do not disable CSRF/auth filters "to make the test pass"; generate a token with `JwtTestTokens`.
- Do not add `permitAll` to a path without a code comment, a test, and a reviewer note.
- Do not commit tokens, passwords, or the demo password; `scripts/run-local.sh` generates them per run.

## Checklist

- [ ] Identity from the token, never from the body, path or header.
- [ ] Every object read compares owner to caller; foreign -> 404.
- [ ] DTOs are allow-lists; entities are never bound from requests.
- [ ] No default secrets; startup fails without them.
- [ ] No tokens, full emails, or request bodies in logs.
- [ ] Errors carry `code` + `correlationId`, never internals.
