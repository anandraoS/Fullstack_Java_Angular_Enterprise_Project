# Secure Coding Guide - OMS (Java 17 / Spring Boot 3.5, Kafka, SQL, Angular 20, React 19)

Audience: anyone writing code in this repository. Pairs with `threat-model.md` (why) and `docs/governance/code-review-checklist.md` (how reviewed). Classes named here were verified under `services/platform-common/src/main/java/com/enterprise/oms/common/`. Snippets that use service-level types (`OrderRepository`, `OrderService`, `OrderController`) are illustrative: those service modules did not exist when this was written; they exist now, but the snippets stay illustrative and the names may differ from the committed code.

Golden rules
1. The backend is the authority. The UI and the gateway are filters, never the only check.
2. Fail closed and fail fast (missing secret = startup failure, unknown role = no access, unknown sort = 400).
3. Never put a secret, token, password, or real personal data in code, config, tests, logs, docs or screenshots.
4. Prefer the shared platform-common building block over hand-rolled code.

## 1. Java and Spring

### 1.1 Authorization: roles AND ownership

Role checks answer "may this kind of user call this?"; ownership checks answer "may this user touch this object?". You need both.

```java
// DON'T (illustrative): trusts the id in the path/body, only checks "logged in"
@GetMapping("/orders/{id}")
public Order get(@PathVariable UUID id) { return orders.findById(id).orElseThrow(); }

// DO (illustrative): role rule + ownership via CurrentUser; foreign order looks like "not found"
@GetMapping("/orders/{id}")
@PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
public Order get(@PathVariable UUID id) {
    CurrentUser me = CurrentUser.require();
    Order o = orders.findById(id).orElseThrow(() -> new NotFoundException("Order", id));
    if (!me.canAccessCustomer(o.getCustomerId())) {
        throw new NotFoundException("Order", id);          // 404 not 403: no enumeration (contract section 4)
    }
    return o;
}
```
* `CurrentUser.canAccessCustomer(UUID)` is true for OPERATOR/ADMIN or for a CUSTOMER whose token `customerId` equals the owner. A missing/malformed `customerId` claim yields no access.
* For list endpoints force the filter from the token, ignore a conflicting query parameter:
```java
UUID customerFilter = me.isCustomer() ? me.customerId() : requestedCustomerId;   // never trust the query for customers
```
* Never read `customerId`, `roles` or actor from the request body when the token has it. Take the actor for audit from `CurrentUser`/`AuditService` (it records the actor from the security context).
* Prefer `@PreAuthorize` per method; use `OmsAuthorizeRequestsCustomizer` only for coarse path rules. `/api/v1/internal/**` is already SERVICE-only in the default chain; do not add a `permitAll` that overlaps it.

### 1.2 Input validation and paging

* Validate at the boundary with Bean Validation on DTO records; on failure throw `RequestValidationException(field, message)` (maps to 400 `VALIDATION_FAILED` with `errors[]`).
* Use DTO records, not JPA entities, as request bodies (prevents mass assignment).
* Paging and sorting go through `PageRequestFactory` with an explicit whitelist:

```java
// DON'T: sort straight from the query string into JPA (sort injection, exceptions leak column names)
Pageable p = PageRequest.of(page, size, Sort.by(sortParam));

// DO
private static final Set<String> SORTABLE = Set.of("createdAt", "status", "totalAmount");
Pageable p = pageRequestFactory.create(page, size, sorts, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
```
`size` outside 1..100, negative `page`, or a field not in the whitelist raises a 400; it is never silently clamped (verified by `PageRequestFactoryTest`).

### 1.3 Idempotency keys

```java
// DO: validate the header and fingerprint the body (illustrative controller)
String key = IdempotencyKeys.require(request.getHeader(IdempotencyKeys.HEADER)); // 400 if missing or malformed
String fp  = IdempotencyKeys.fingerprint(canonicalBody);
// look up (callerId, key): same fp -> replay original; different fp -> throw new IdempotencyKeyReusedException(...)
```
* Scope the stored key to the authenticated caller (`CurrentUser.id()`), otherwise one user can probe another user's keys.
* Do not log the full key together with the body; the key is not a secret but is a correlation handle.
* Replay returns the original response and header `Idempotent-Replay: true`; never re-run the side effects.

### 1.4 Errors

* Throw `OmsException` subclasses (`NotFoundException`, `ConflictException`, `InvalidStateTransitionException`, `ForbiddenException`...) and let `GlobalExceptionHandler` build the RFC 7807 body.
* In filters or security code outside `@ControllerAdvice`, use `ProblemWriter`:
```java
// DON'T
response.setStatus(401);
response.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");   // hand-built JSON, leaks exception text

// DO
problemWriter.write(request, response, ErrorCode.UNAUTHENTICATED, "Authentication required");
```
* Never return `e.getMessage()`, stack traces, SQL or class names. `server.error.include-*` are already `never` in `oms-defaults.properties`; do not override them.
* The UI must not echo `detail` for authentication failures.

### 1.5 JWT and secrets

```java
// DON'T
private static final String SECRET = "change-me-please-change-me-please!!";   // hard-coded, committed
@Value("${oms.security.jwt.secret:devsecret}") String secret;                  // silent weak default

// DO: let platform-common resolve it; it fails startup when absent or < 32 bytes
JwtSecret secret = JwtSecret.resolve(props, env);      // env OMS_JWT_SECRET, no default in prod
```
* Tests: `JwtSecret.random()` or `JwtTestTokens` from platform-test-support. `oms.security.jwt.allow-ephemeral-secret=true` is for tests and single-process runs only; never in a manifest.
* Do not log `JwtSecret` or tokens. `JwtSecret.toString()` is safe by design; keep it that way.
* Validate exp, iss (`oms-dev-issuer` in demo), aud (`oms-api`) and signature (done in `JwtSecurityAutoConfiguration`); do not write a second, weaker decoder. Do not accept `alg: none` or switch algorithms from the token header.
* Service calls: use `OmsRestClientFactory.createServiceClient(baseUrl)`, which attaches a short-lived SERVICE token from `ServiceTokenProvider` and applies connect 2 s / read 3 s timeouts. Do not forward the end user's token to downstream services as a shortcut, and do not build a `RestClient` without timeouts.
* Production: move to RS256/JWKS (`oms.security.jwt.jwk-set-uri`); see `threat-model.md` section 9.

### 1.6 Logging with `LogMasking`

```java
// DON'T
log.info("login failed for {} password={} token={}", email, pwd, bearer);
log.info("customer note: " + userText);                         // CR/LF log forging

// DO
log.info("order created orderId={} customer={}", orderId, LogMasking.maskEmail(email));  // a***@example.test
log.warn("rejected header value={}", LogMasking.stripNewlines(userSuppliedValue));
log.error("upstream failed: {}", LogMasking.sanitize(ex.getMessage()));                  // masks emails, bearer, JWT, password=...
```
* `LogMasking.maskEmail`, `maskToken`, `sanitize`, `stripNewlines` are opt-in: apply them at every call site that touches user-controlled or sensitive text. Reviewers check this.
* Put identifiers (orderId, correlationId) in log fields or MDC; never use them as metric tags (cardinality) and never log whole request bodies or whole events (they contain customer email).
* Audit `detail` (up to 8000 chars, `AuditService.record`) is free text: store ids and state names, not raw payloads or PII.
* Correlation id: rely on `CorrelationIdFilter` (accepts a safe inbound id, otherwise generates a UUID). Never trust it for identity.

## 2. SQL and persistence

```java
// DON'T: concatenation (injection, also breaks plan caching)
em.createNativeQuery("SELECT * FROM orders WHERE status = '" + status + "' ORDER BY " + sort).getResultList();

// DO: bind parameters; sort via PageRequestFactory whitelist
@Query("select o from Order o where o.customerId = :customerId and (:status is null or o.status = :status)")
Page<Order> search(@Param("customerId") UUID customerId, @Param("status") OrderStatus status, Pageable pageable);
```
(`OutboxEventRepository.deletePublishedBefore(@Param("before") Instant before)` is the verified in-repo example of named binding.)

* Money: `BigDecimal`, scale 2, never `double`. Time: UTC `Instant`.
* Stock changes use the atomic conditional UPDATE from contract section 7 (`WHERE on_hand - reserved >= :q`), never read-modify-write in Java.
* State change, history, audit and outbox event in ONE `@Transactional` method. `OutboxService.append(...)` throws without an active transaction by design.
* Migrations: next Flyway version in the service range (`V10+`); portable SQL (PostgreSQL 16 and H2 MODE=PostgreSQL); never edit an applied migration; no real data in seed files (`V1000+`, dev profile only).
* `spring.jpa.open-in-view=false` and `ddl-auto=none` are platform defaults; do not enable schema auto-update.
* Production DB role for the app: DML only; Flyway uses a separate role; `audit_events` INSERT/SELECT only.

## 3. Kafka and events

```java
// DON'T: publish directly from the request thread (lost or phantom events), process without de-duplication
kafkaTemplate.send("oms.order.events.v1", orderId, json);
handle(event);

// DO: outbox in the same transaction; consumer through the idempotent processor
outboxService.append("oms.order.events.v1", orderId.toString(), envelope);          // inside @Transactional
idempotentEventProcessor.processOnce("order-service", envelope, () -> applyEffect(envelope));
```
* Consumers are tolerant readers (ignore unknown properties) but must validate what they use: ids are UUIDs, quantities in range, `aggregateId` matches payload `orderId`.
* Bad input is not retried: throw `NonRetryableEventException("reason")` (or `MalformedEventException` when parsing fails) so it goes straight to `<topic>.DLT`; transient errors are rethrown so the `DefaultErrorHandler` retries (4 attempts, backoff).
* Never trust an event just because it arrived on a topic. Production: Kafka TLS + SASL + ACLs; consumer checks that `producer` owns the topic. Treat DLT contents as sensitive (they contain full payloads) and restrict access.
* Keys are `orderId` (ordering per order). Do not put PII in the key or in Kafka headers.
* Additive-only event changes; consumers deploy before producers. Never rename a field within `schemaVersion` 1.
* Do not claim exactly-once. Say: at-least-once delivery, effectively-once business effect.

## 4. HTTP surface hygiene

* Keep CSRF disabled only because the API is stateless bearer-token (as configured); never add cookie-based auth without re-enabling CSRF.
* Keep the platform security headers (nosniff, frame deny, no-referrer, HSTS); do not disable them to fix a dev problem.
* Request bodies: JSON only, 64 KB cap by `RequestSizeLimitFilter`; do not enable multipart.
* CORS: allow-list origins via `OMS_GATEWAY_CORS_ALLOWED_ORIGINS`; never `*` with credentials. The UI is same-origin and needs no CORS.
* Actuator: only `health,info,prometheus` exposed; never `env`, `heapdump`, `beans`. In production restrict to the management network.
* Outbound calls: only to configured base URLs; never fetch a URL taken from user input (no SSRF surface exists today; keep it that way).

## 5. Angular (frontend/oms-ui)

```ts
// DON'T
el.nativeElement.innerHTML = order.statusReason;                       // XSS
this.sanitizer.bypassSecurityTrustHtml(userText);
localStorage.setItem('token', jwt);                                    // survives browser restarts, readable by any script
console.log('token', token);
this.http.get(`${base}/orders?customerId=${id}`);                      // string-built URLs with unvalidated input

// DO
// template: {{ order.statusReason }}  (Angular escapes interpolation)
// params:
this.http.get(`${base}/orders`, { params: { status, page } });         // HttpParams encode values
```
* Token handling (verified in `core/auth/auth.store.ts`): token held in memory and mirrored to `sessionStorage` (per tab, cleared on close) with the XSS trade-off documented in the file; never `localStorage`, never in URLs, never logged. `authInterceptor` adds `Authorization` only to API calls and never to `/auth/token` or foreign origins; keep both conditions.
* `correlationInterceptor` adds `X-Correlation-Id` to API calls; show the correlation id in error toasts so support can find logs, but never show server `detail` for 401.
* Retries: the interceptor retries only idempotent GETs on transient errors (max 2). Never auto-retry POST without the same `Idempotency-Key`.
* Route guards (`core/auth/guards.ts`) hide screens; they are not security. Assume every button can be invoked by a hostile user.
* Runtime config (`config.json`) holds only non-secret values (`apiBaseUrl`, poll intervals). No keys in the bundle or `environment.ts`.
* Content Security Policy at nginx (`frontend/oms-ui/nginx.conf`): production recommendation is `default-src 'self'` with no inline script; confirm before release (not verified here).
* Keep dependencies on the lockfile (`npm ci --ignore-scripts` in CI).

## 5b. React (frontend/oms-ui-react)

```tsx
// DON'T
<div dangerouslySetInnerHTML={{ __html: order.statusReason }} />      // XSS
localStorage.setItem('token', jwt);                                    // survives browser restarts, readable by any script
fetch(`${base}/orders?customerId=${id}`);                              // string-built URLs with unvalidated input
useEffect(() => { setInterval(poll, 1500); }, []);                     // never cleared, duplicates per mount

// DO
<Typography>{order.statusReason}</Typography>                          // React escapes text nodes
// RTK Query endpoint: query: (a) => ({ url: '/orders', params: toParams(a) })   (params are encoded)
// polling: useOrderPolling (RTK Query pollingInterval, released on unmount)
```
* Token handling (`src/store/authSlice.ts`): Redux memory plus `sessionStorage`, never `localStorage`, never in URLs, never logged; attached only by `omsBaseQuery` to same-origin API calls and never to `/auth/token`. XSS can read it: production should use PKCE plus a BFF with HttpOnly cookies.
* Show the correlation id ("Reference") in errors; never show server `detail`; sign-in failures use one generic sentence.
* `RequireAuth` and `RequireRole` are UX only; every call is authorized by the backend.
* `returnUrl` must stay a same-app relative path; keep the check when touching the login flow.
* CSP keeps `style-src 'unsafe-inline'` only for MUI/Emotion; do not widen `script-src`. No CDN fonts or scripts.
* Never auto-retry a POST; keep one `Idempotency-Key` per logical submission (`IdempotencyKeyHolder`).
* On logout reset the API cache and the cart (listener middleware in `src/store/index.ts`), otherwise the next user of the tab sees cached data.

## 6. Dependency and supply-chain hygiene

* Versions are pinned in `services/pom.xml` (Spring Boot 3.5.16, Spring Cloud 2025.0.0, springdoc 2.8.17, resilience4j 2.2.0, logstash-logback-encoder 8.0, JaCoCo 0.8.13). Upgrade deliberately with the test suite; do not use version ranges or `LATEST`.
* Add a dependency only with a reason in the PR; prefer what the Spring BOM already manages; check licence and maintenance.
* CI scanners (defined in `.github/workflows/ci.yml`, never executed): Trivy (Maven + npm lockfiles, IaC misconfig, images), `npm audit`, Semgrep, CodeQL, gitleaks. A finding with an available fix at HIGH/CRITICAL blocks the merge.
* Before production: pin GitHub Actions to commit SHAs, pin base images by digest, generate an SBOM, sign images, enable Dependabot or Renovate.
* Never run downloaded scripts/archives from inside their own directory; verify checksums (see `scripts/fetch-validation-tools.mjs`, checksum-verified tool fetch).
* Containers: non-root (uid 10001), read-only root filesystem, dropped capabilities, no `latest` tags (matches `infrastructure/kubernetes/base/*`).
* Secrets scanning: install a pre-commit gitleaks hook locally.

## 7. Testing security behaviour

* Every endpoint ships with a negative test: no token (401), wrong role (403), foreign resource (404), bad input (400), oversize body (413), invalid state (409). Model them on `SecurityApiTest` in platform-common.
* Generate secrets and tokens at runtime (`JwtTestTokens`); never commit a token, even an expired one.
* Idempotent handlers need a "second delivery is a no-op" test (model: `IdempotentProcessorAndAuditTest`).
* Log-safety: add assertions with `LogMasking` for new log statements that carry user text.
* Docker-dependent tests (`AbstractPostgresIT`) are skipped without Docker: say so in the PR and require CI evidence.

## 8. Quick reference

| Need | Use | Do not |
|---|---|---|
| Who is calling | `CurrentUser.require()` | read `customerId` from body/query |
| May they see this customer's data | `CurrentUser.canAccessCustomer(owner)` | compare ids by hand, return 403 for foreign orders |
| Paging/sort | `PageRequestFactory.create(...)` with whitelist | `PageRequest.of(.., Sort.by(userInput))` |
| Idempotent create | `IdempotencyKeys.require` + `fingerprint` | accept missing key silently |
| Error body | throw `OmsException` subclass / `ProblemWriter` | hand-built JSON, `e.getMessage()` |
| Log user text | `LogMasking.sanitize` / `maskEmail` / `stripNewlines` | log tokens, passwords, full emails |
| Publish event | `OutboxService.append` in the same tx | `kafkaTemplate.send` in a request path |
| Consume event | `IdempotentEventProcessor.processOnce` | process without de-dup; swallow poison |
| Call another service | `OmsRestClientFactory.createServiceClient` | `RestTemplate` without timeouts, forward user token |
| Secret | env/`secretKeyRef`, `JwtSecret.resolve` | literal, default value |
