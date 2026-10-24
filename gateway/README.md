# api-gateway

Edge of the OMS: reactive Spring Cloud Gateway (Spring Boot 3.5.16, Spring Cloud 2025.0.0, Java 17). Contract:
`docs/architecture/00-shared-contract.md` (sections 2, 4, 5, 5.5, 12). Maven module `api-gateway` (physically `gateway/`, in the
`services/pom.xml` reactor through a file-activated profile). Runnable jar: `gateway/target/api-gateway-1.0.0.jar`, port **18080**.

```
JAVA_HOME=<jdk17> mvn -B -f services/pom.xml -pl ../gateway verify        # build + 141 tests
OMS_JWT_SECRET=... OMS_DEMO_PASSWORD=... SPRING_PROFILES_ACTIVE=dev java -jar gateway/target/api-gateway-1.0.0.jar
```

## Responsibilities

| Concern | Implementation |
|---|---|
| Routing | static route table from properties (no discovery); `config/RoutesConfig` |
| Authentication | stateless JWT resource server, **HS256 pinned**, validates signature, `exp`/`nbf` (30 s skew), `iss=oms-dev-issuer`, `aud=oms-api`, requires `exp` and `sub`. 401 `application/problem+json` (`UNAUTHENTICATED`, `WWW-Authenticate: Bearer`, never says why). Token only from the `Authorization` header (no query parameter). OIDC mode: `oms.security.jwt.issuer-uri` / `jwk-set-uri` (RS256; not covered by tests) |
| Edge authorization | role rules per route (table below), 403 `FORBIDDEN`. **Defense in depth only**: backends re-validate the JWT and stay authoritative (ownership, "other customer's order is 404", state rules). `SERVICE` tokens and unknown roles never pass the edge |
| Internal paths | everything under `/api/v1/internal` answers 404 (before authentication, also for SERVICE/ADMIN tokens, case/`//`/percent-encoding variants); it is also not routed and `denyAll` in the security rules |
| Path hygiene | `..`, `.`, `%2f`, `%5c`, `%00`, `;` (matrix params), double encoding, control characters -> 400 `BAD_REQUEST` |
| Header hygiene | strips client-supplied `X-User-*`, `X-Roles`, `X-Customer-Id`, `X-Auth-*`, `X-Internal-*`, `X-Service-*`, `Forwarded`, `X-Forwarded-*`, `X-Real-Ip`, method-override/rewrite headers; gateway adds its own `X-Forwarded-*`; SCG removes hop-by-hop headers. The gateway never injects identity headers: backends read the forwarded `Authorization` |
| Correlation id | accepts an inbound `X-Correlation-Id` matching `[A-Za-z0-9._-]{1,64}`, otherwise generates a UUID; forwarded upstream, set on **every** response (overwrites the upstream echo, so exactly one value), included in problem bodies and access logs |
| Request size | 64 KB (`oms.gateway.max-body-bytes`): declared `Content-Length` over the limit is rejected unread; chunked bodies are buffered up to 64 KB and rejected over it. 413 `PAYLOAD_TOO_LARGE` |
| Timeouts | connect 2 s, response 10 s (netty client, `spring.cloud.gateway.server.webflux.httpclient.*`) |
| Circuit breaker | Resilience4j per upstream (`order`, `inventory`, `fulfillment`, `notification`); window 20 / min 10 calls / 50 % / 10 s open (`oms.gateway.circuit-breaker.*`). Connect failure, timeout or open circuit -> `forward:/fallback/{svc}` -> 503 `UPSTREAM_UNAVAILABLE` + `Retry-After: 5`. Upstream 4xx/5xx *responses* are passed through untouched (and do not trip the breaker). No automatic retries (POSTs are not safely repeatable here) |
| Rate limiting | token bucket per **JWT subject** (after authentication) or per peer IP when unauthenticated; capacity 100, 50 tokens/s (`oms.gateway.rate-limit.*`); 429 `RATE_LIMITED` + `Retry-After`; `X-RateLimit-Remaining` header; actuator exempt. In-memory by default (bounded to 100k keys, idle purge, overflow bucket). `oms.redis.enabled=true` switches to Spring Cloud Gateway's `RedisRateLimiter` (shared across replicas) and any Redis error degrades to the in-memory limiter for that request |
| Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP `default-src 'none'; frame-ancestors 'none'`, `Permissions-Policy`, `Cache-Control: no-cache, no-store...` (unless upstream set it), HSTS on HTTPS |
| CORS | none by default (same-origin: nginx / `ng serve` proxy). `OMS_GATEWAY_CORS_ALLOWED_ORIGINS=https://a,https://b` (exact origins; `*` fails startup); exposes `Idempotent-Replay, Location, X-Correlation-Id, Retry-After, X-RateLimit-Remaining`; no credentials |
| Errors | all gateway-generated errors are contract problem+json (`type, title, status, detail, instance, code, correlationId, timestamp, errors`), no stack traces; the original request path is used for `instance` |
| Dev tokens | `POST /api/v1/auth/token`, **profile `dev` only** (the bean does not exist otherwise; without `dev` the path is simply unauthenticated -> 401 / 404) |
| Aggregate health | `GET /api/v1/ops/health` (OPERATOR/ADMIN) |
| Observability | actuator `health` (+ liveness/readiness), `info`, `prometheus` on the same port; access log `oms.gateway.access`; JSON logs with profile `json-logs` (fields `timestamp, level, service, correlationId, traceId, spanId, logger, message`) |

## Routes (contract section 5.5)

| Path | Upstream (default `oms.gateway.routes.<svc>.uri`) | Edge roles |
|---|---|---|
| `/api/v1/customers[/**]`, `/api/v1/orders[/**]`, `/api/v1/admin/orders/**`, `/api/v1/admin/saga/**`, `/api/v1/admin/reconciliation/**` | order `http://localhost:18081` (`OMS_GATEWAY_ROUTES_ORDER_URI`) | customers: POST ADMIN/OPERATOR, list OPERATOR/ADMIN, get-by-id any of the 3; orders: CUSTOMER/OPERATOR/ADMIN; admin orders/saga OPERATOR/ADMIN; reconciliation ADMIN |
| `/api/v1/products[/**]`, `/api/v1/inventory[/**]` | inventory `:18082` (`OMS_GATEWAY_ROUTES_INVENTORY_URI`) | products GET any authenticated role, write ADMIN; inventory OPERATOR/ADMIN |
| `/api/v1/fulfillments[/**]`, `/api/v1/admin/fulfillments/**` | fulfillment `:18083` (`OMS_GATEWAY_ROUTES_FULFILLMENT_URI`) | OPERATOR/ADMIN |
| `/api/v1/notifications[/**]`, `/api/v1/admin/notifications/**` | notification `:18084` (`OMS_GATEWAY_ROUTES_NOTIFICATION_URI`) | notifications CUSTOMER/OPERATOR/ADMIN; admin OPERATOR/ADMIN |
| `/api/v1/ops/{order,inventory,fulfillment,notification}/**` | same service, rewritten to `/api/v1/ops/**` (`/api/v1/ops/order/messaging` -> `/api/v1/ops/messaging`) | OPERATOR/ADMIN |
| `/api/v1/ops/health` | local (aggregate) | OPERATOR/ADMIN |
| `/api/v1/auth/token` | local, profile `dev` | public |
| `/api/v1/internal/**` | never routed, 404 | - |
| anything else | 404 problem+json (401 when anonymous) | - |

`GET /api/v1/ops/health` calls every service's `/actuator/health/readiness` in parallel (timeout `oms.gateway.health.timeout`,
2 s). Always HTTP 200: `{"status":"UP|DOWN|DEGRADED","services":{"order":{"status":"UP|DOWN","detail":null|"HTTP 503"|"timeout"|"unreachable"},...}}`
(all up = `UP`, none up = `DOWN`, else `DEGRADED`). Details are generic, so no internal hosts or exception text leak.

## Dev token endpoint

`POST /api/v1/auth/token` `{ "username", "password" }` -> `{ accessToken, tokenType:"Bearer", expiresIn:3600, username, roles, customerId|null }`.
Users: `customer1` (CUSTOMER, `c0000000-...-0001`), `customer2` (CUSTOMER, `...0002`), `operator1` (OPERATOR), `admin1` (ADMIN); one shared
password from env `OMS_DEMO_PASSWORD` (property `oms.demo.password`). **No default: with profile `dev` the application refuses to start if it is
unset or shorter than 8 characters.** Only a SHA-256 digest is kept, compared with `MessageDigest.isEqual` (constant time, same work for unknown
users, identical 401 for wrong user and wrong password). Brute force: 5 failures per (ip, username) or 20 per ip within 5 min lock that key
for 5 min with 429 + `Retry-After` (even the correct password is refused while locked; success clears the pair counter). Credentials are never
logged (only the sanitised, truncated username). `Cache-Control: no-store`. Not an identity provider: production uses an OIDC issuer.

## Configuration

| Env / property | Default | Notes |
|---|---|---|
| `OMS_JWT_SECRET` (`oms.security.jwt.secret`) | none | >= 32 bytes, **required** (startup fails). Same value as the services |
| `OMS_DEMO_PASSWORD` | none | required only in profile `dev` |
| `OMS_GATEWAY_ROUTES_{ORDER,INVENTORY,FULFILLMENT,NOTIFICATION}_URI` | `http://localhost:1808{1..4}` | http/https with host, validated at startup |
| `OMS_GATEWAY_CORS_ALLOWED_ORIGINS` | empty (same-origin) | exact origins |
| `OMS_REDIS_ENABLED` (`oms.redis.enabled`) | `false` | Redis rate limiter; `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` for the connection |
| `OMS_GATEWAY_RATE_LIMIT_CAPACITY`, `_REFILL_PER_SECOND`, `_ENABLED` | 100 / 50 / true | |
| `OMS_GATEWAY_CIRCUIT_BREAKER_*`, `OMS_GATEWAY_HEALTH_TIMEOUT`, `OMS_GATEWAY_THROTTLE_*`, `OMS_GATEWAY_MAX_BODY_BYTES` | see `application.yml` | |
| `SERVER_PORT` | 18080 | |
| `SPRING_PROFILES_ACTIVE` | - | `dev` (token endpoint), `local` (debug logging), `prod` (nothing implicit), `json-logs` (JSON log lines) combinable (`dev,local` in `scripts/run-local.sh`) |

## Security boundaries

* The gateway is the only component reachable from outside; services must not be exposed (K8s network policy / Compose network). Services
  trust the JWT, not the gateway, so a request that bypasses it is still authenticated and authorised by the service.
* Edge rules are a coarse filter; they intentionally do not model resource ownership (that needs data): a CUSTOMER passes the edge for
  `/api/v1/orders/{id}` and the order-service answers 404 for other customers' orders.
* `/actuator/prometheus` and health are unauthenticated on the same port as the API (contract section 5.5) but are not routed to services.
  In production restrict them with a network policy or move management to a separate port (`management.server.port`).
* Rate limiting keys by the TCP peer address for anonymous callers. Behind a load balancer that is the LB's address (anonymous callers share a
  bucket; authenticated callers are keyed by `sub` and unaffected). `X-Forwarded-For` is deliberately not trusted.
* Edge limits do not replace service limits (services still enforce 64 KB, paging limits, etc.).

## Failure modes

| Situation | Behaviour |
|---|---|
| Upstream down / connect refused | 503 `UPSTREAM_UNAVAILABLE` (fallback); breaker opens after the configured failure rate; half-open probes after 10 s |
| Upstream slower than 10 s | netty response timeout -> 503 `UPSTREAM_UNAVAILABLE`, counted by the breaker |
| Upstream returns 4xx/5xx | passed through unchanged, not counted by the breaker (a backend that is up but returning 500 is not "unavailable") |
| Redis down (`oms.redis.enabled=true`) | rate limiting falls back to in-memory for the request; Redis is excluded from readiness |
| Service down | gateway stays ready (`/actuator/health/readiness` does not depend on upstreams); `/api/v1/ops/health` reports it |
| Missing/short `OMS_JWT_SECRET`, bad route URI, wildcard CORS, missing demo password in `dev` | startup fails (fail fast) |
| Token endpoint under attack | per (ip, username) and per ip lockout; the bucket map is bounded (no memory growth) |

## Scaling

Stateless: run N replicas behind a load balancer. In-memory state (rate-limit buckets, login throttle, breaker state) is per replica, so the
effective limit is N x configured; set `oms.redis.enabled=true` for a shared rate limiter (the breaker and the dev-only throttle stay local).
Reactor Netty needs few threads; size memory for the 64 KB chunked-body buffering (at most 64 KB per in-flight chunked request) and the
connection pool. Circuit-breaker time limiter (12 s) must stay above the 10 s response timeout.

## OpenAPI aggregation

Each service publishes `/v3/api-docs` (springdoc, public in the service, **not routed** by the gateway so the contract surface is not widened).
No runtime aggregation is done. `node scripts/export-openapi.mjs` fetches every service's document from a running stack (default ports
18081-18084, overridable with `OMS_ORDER_URL`, ...) and writes normalised files to `specs/openapi/<service>.json`;
`--check` compares instead of writing (CI drift check). Gateway-only endpoints (`/auth/token`, `/ops/health`) are documented in the contract.

## Tests (`mvn verify`, 141 tests)

Stub upstreams are JDK `HttpServer`s (`StubServer`); tokens are minted with a per-run random secret. Covered: routing and ops rewrite,
internal-path 404, traversal rejection, header stripping/forwarding, response-header pass-through and de-duplication, security headers,
correlation id (generate / echo / replace malformed / overwrite upstream echo / problem body), 413 (declared, chunked, exact limit), 401 for
missing/garbage/expired/forged/wrong-aud/wrong-iss/alg=none/HS512/no-exp/no-sub/query-token, 47-case role matrix, CORS (allowed, rejected, default
none, wildcard fails startup), 429 + `Retry-After`, timeout and connection-refused fallback, breaker open short-circuit, upstream 500 pass-through,
token endpoint (all users, claims, wrong password, unknown user, validation, throttle per pair and per ip), fail-fast startup rules,
`/api/v1/ops/health` (up, degraded, timeout, unreachable, all down, roles), actuator exposure, unit tests of the limiter, throttle and path guard.

Not covered: Redis rate limiter and OIDC/JWKS mode (no Redis / IdP available), the connect timeout against a black-holed address, behaviour behind a
real load balancer, performance.
