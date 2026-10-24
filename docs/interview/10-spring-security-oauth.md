# Spring Security 6, JWT Resource Servers and OAuth2/OIDC: What the Repo Does and What Production Needs

This chapter covers application security from the Spring side, with a front-end footnote. The platform runs Spring Boot 3.5.16 with Spring Security 6.5.x and the OAuth2 resource-server module. Every servlet service (order, inventory, fulfillment, notification) gets the same stateless JWT security from the shared starter in `services/platform-common`; the reactive gateway has its own chain (`gateway/src/main/java/com/enterprise/oms/gateway/security/EdgeSecurityConfig.java`). The decision record is `docs/adr/0010-jwt-oidc-security-model.md`; the threat analysis is `docs/security/threat-model.md`. A theme runs through the whole chapter: the repo is honest that it ships a demo identity provider (HS256 tokens minted by a dev-only endpoint), and the interview skill is to explain precisely what changes in production.

## Big picture and kid-friendly analogy

Imagine a theme park.

* **Authentication** is the ticket booth: "who are you, and can you prove it?" You show something and get a wristband.
* **A JWT** is the wristband. It is printed with your name, your ticket type (roles), a colour for today (the expiry), the park's stamp (the signature) and the park's name (issuer). Any ride operator can check the stamp without calling the booth. But a wristband cannot be taken back once it is on your wrist, so it is made to expire quickly.
* **Authorization** is the height bar at the roller coaster: "are you allowed on this ride?" That is role checks (`hasRole('ADMIN')`).
* **BOLA / IDOR** (broken object level authorization) is the lost-property desk. Having a wristband lets you into the desk, but the clerk must still check that the umbrella you point at is yours. If the clerk just hands over whatever label you say, any visitor can walk off with anyone's bag. Checking the ride height is not enough; you also check the owner of the object.
* **The gateway** is the main gate with a guard who checks wristbands and turns away troublemakers; each ride operator still checks again, because someone might climb the fence.
* **A service token** is the staff-only pass that workers use to move between kitchen and shop; visitors can never be issued one.
* **Rate limiting** is the turnstile that only lets so many people through per minute.
* **CORS** is the park rule about which other parks' buses may drop visitors at your gate; **CSRF** is a trickster who uses your automatic wristband (a cookie your browser attaches by itself) to make you ride something you did not choose.
* **OAuth2 with PKCE** is the park's official booth system: you do not give your password to every ride; you go to the booth, prove who you are there, and the booth hands your phone a short-lived wristband plus a secret handshake that only your phone knows.

| Analogy | Security concept |
|---|---|
| Ticket booth and wristband | Authentication, JWT access token |
| Stamp on the wristband | JWS signature (HS256 or RS256) |
| Ride height bar | Role-based authorization, `@PreAuthorize` |
| Lost-property clerk checking the owner | Resource-level check (BOLA/IDOR prevention) |
| Main gate plus each ride | Gateway validation plus per-service validation |
| Staff pass | SERVICE role token |
| Turnstile | Rate limiting |
| Rules about other parks' buses | CORS |
| Tricking an automatic wristband | CSRF (needs ambient credentials like cookies) |
| Booth plus secret handshake | Authorization code flow with PKCE |

## Beginner tier

### Example 1: the security filter chain, stateless and deny by default

In Spring Security 6 a `SecurityFilterChain` bean defines which servlet filters protect which requests. The shared chain lives in `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/JwtSecurityAutoConfiguration.java`:

```java
http
        // CSRF off: stateless bearer-token API, no cookies/sessions (see class javadoc).
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(h -> h
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(Customizer.withDefaults()))
        .authorizeHttpRequests(a -> {
            a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                    "/actuator/prometheus").permitAll();
            a.requestMatchers("/api/v1/internal/**").hasRole(Roles.SERVICE);
            customizers.orderedStream().forEach(c -> c.customize(a));
            a.anyRequest().authenticated();
        })
        .oauth2ResourceServer(o -> o
                .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(denied));
```

How to narrate it. A request enters the filter chain; the bearer-token filter extracts `Authorization: Bearer <jwt>`, hands it to the `JwtDecoder`, and on success places a `JwtAuthenticationToken` in the `SecurityContext`; then the authorization filter evaluates the first matching `requestMatchers` rule. Rules are evaluated in order and the first match wins, which is why specific rules come before the catch-all and the catch-all is `anyRequest().authenticated()`, a deny-by-default posture: a new endpoint nobody remembered to list still needs a valid token. `hasRole("SERVICE")` checks for the authority `ROLE_SERVICE`; `hasAuthority("SERVICE")` would not match. `STATELESS` means no `HttpSession` is ever created, so any replica can serve any request and there is nothing to steal from a session store. 401 and 403 are written as RFC 7807 problem bodies by `ProblemSecurityHandlers` so clients get a consistent error shape.

Two honest observations about this chain. Spring Security 6 deprecated the chained `.and()` style in favour of the lambda DSL used here. And `/actuator/prometheus`, health and the OpenAPI/Swagger paths are `permitAll` on the service port; `docs/security/threat-model.md` section 8 lists this as a known demo default (production should use a separate management port plus NetworkPolicy, and restrict API docs).

### Example 2: roles from the token, and method security as a second layer

The JWT carries a `roles` claim, and the chain converts it to Spring authorities (same file):

```java
static Collection<GrantedAuthority> authorities(Jwt token, String claim) {
    Object raw = token.getClaims().get(claim);
    List<String> roles = raw instanceof Collection<?> c ? c.stream().map(String::valueOf).toList()
            : raw instanceof String s ? List.of(s.split("[ ,]+")) : List.of();
    return roles.stream().map(r -> r.trim().toUpperCase(Locale.ROOT)).filter(Roles.ALL::contains)
            .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toList());
}
```

Only the four known roles (`CUSTOMER`, `OPERATOR`, `ADMIN`, `SERVICE`) become authorities; anything else in the claim is ignored, so a token cannot invent a privileged role name. Controllers then add method security. `@EnableMethodSecurity` is on the auto-configuration, and `OrderController` (`services/order-service/src/main/java/com/enterprise/oms/order/api/OrderController.java`) declares `private static final String ROLES = "hasAnyRole('CUSTOMER','OPERATOR','ADMIN')";` and puts `@PreAuthorize(ROLES)` on every endpoint. Order-service additionally registers URL-level rules through an `OmsAuthorizeRequestsCustomizer` bean (`services/order-service/src/main/java/com/enterprise/oms/order/config/OrderSecurityConfig.java`), whose Javadoc gives the reason: URL rules run before request binding and validation, so an unauthorized caller gets 403 whatever the body looks like, which avoids a validation-error oracle. The annotations stay as "a second layer (defense in depth)". In Spring Security 6, `@EnableMethodSecurity` replaced `@EnableGlobalMethodSecurity`, enables `@PreAuthorize`/`@PostAuthorize` by default, and uses AOP proxies, so the self-invocation rule from the Spring chapter applies: calling a secured method from another method of the same class bypasses the check.

## Intermediate tier

### Example 1: validating the token properly (algorithm, issuer, audience, expiry)

Decoding is not validating. The decoder bean in `JwtSecurityAutoConfiguration` pins the algorithm and attaches validators:

```java
decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getObject().bytes(), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256).build();
issuerValidator = new JwtIssuerValidator(jwt.getIssuer());
// ...
OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
        aud -> aud != null && aud.contains(jwt.getAudience()));
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(jwt.getClockSkew()),
        issuerValidator, audience));
```

Why each piece. **Pinning HS256** blocks algorithm-confusion attacks: a token whose header says `alg: none`, or `RS256` (tricking a library into using a public key as an HMAC secret), is rejected because the decoder accepts only the configured MAC algorithm. `docs/security/threat-model.md` records that `EdgeSecurityTest` covers `alg:none` and wrong-HMAC-algorithm tokens in the gateway. **Issuer** (`iss`) and **audience** (`aud` must contain `oms-api`) stop a valid token from one system, or intended for another API, being replayed here. **`JwtTimestampValidator` with 30 seconds of clock skew** (`OmsSecurityProperties.Jwt.clockSkew`) checks `exp` and `nbf` while tolerating small clock differences between machines. The gateway adds a custom validator that requires `exp` and `sub` to be present, with the comment "the timestamp validator alone accepts tokens without exp" (`EdgeSecurityConfig.requiredClaims`).

The secret is handled with fail-fast discipline (`services/platform-common/src/main/java/com/enterprise/oms/common/security/JwtSecret.java`): it comes from property `oms.security.jwt.secret` or environment `OMS_JWT_SECRET`, must be at least 32 bytes (the HS256 key-size floor), and startup throws `IllegalStateException("JWT secret missing ...")` otherwise. The only escape is `allow-ephemeral-secret=true`, which generates a random in-memory secret and logs a warning; the contract confines it to tests and single-process runs. `toString()` returns `JwtSecret[redacted, N bytes]` so the key cannot leak through logging. `scripts/run-local.sh` generates a random value per run and no secret is committed.

### Example 2: BOLA and IDOR, the bug that role checks cannot catch

The platform treats this as the highest-risk class (`docs/code-review/02-bola-customerId-from-body.md`, threat T2). `CurrentUser` (`services/platform-common/src/main/java/com/enterprise/oms/common/security/CurrentUser.java`) is the identity helper, and its core method is:

```java
/** True if the caller is staff or is the customer identified by {@code ownerCustomerId}. */
public boolean canAccessCustomer(UUID ownerCustomerId) {
    return isStaff() || (isCustomer() && customerId != null && customerId.equals(ownerCustomerId));
}
```

The `customerId` comes from the verified JWT claim, never from the request. Reads use it as a filter that turns "not yours" into "not found" (`OrderQueryService.visibleOrder`):

```java
private CustomerOrder visibleOrder(UUID id, CurrentUser user) {
    return orders.findWithItemsById(id).filter(o -> user.canAccessCustomer(o.getCustomerId()))
            .orElseThrow(() -> new NotFoundException("Order", id));
}
```

Returning 404 rather than 403 for another customer's order means an attacker cannot enumerate which order ids exist. For lists, the filter is forced: in `OrderQueryService.search` a non-staff caller's `customerId` query parameter is overwritten with the token's own id (`customerFilter = user.customerId();`), so `GET /orders?customerId=<victim>` quietly returns the caller's own orders. For create, the body's `customerId` must equal the token's, otherwise 403 (`OrderCommandService`: `"customerId does not match the authenticated customer"`). Interview framing: a method-level role check answers "what kind of caller are you"; ownership checks answer "is this object yours"; you need both, and the second must run against data loaded from the database with an identity taken from the token. UUIDs are not secrets and unguessability is not authorization.

## Advanced tier

### Example 1: edge versus service validation, CORS, CSRF and untrusted headers

The gateway is a second, independent validation point; services do not trust it. Reasons in ADR-0010: "Trust gateway headers: any bypass of the gateway (misrouted network policy) becomes full privilege escalation." So the gateway forwards the original `Authorization` header and never injects identity headers, and it strips inbound ones a client might forge (`gateway/src/main/java/com/enterprise/oms/gateway/filter/EdgeFilters.java`, `InboundHeaderSanitizer`: `x-user-*`, `x-roles`, `x-customer-id`, `x-forwarded-*`, `x-internal-*`, and similar). `/api/v1/internal/**` is answered 404 before authentication and is `denyAll` in the edge chain, and SERVICE-role tokens are never accepted at the edge; internal endpoints require `hasRole("SERVICE")` in each service.

CORS is configured by exact origin only (`EdgeSecurityConfig.corsConfigurationSource`):

```java
List<String> origins = props.getCors().getAllowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
if (origins.stream().anyMatch(o -> o.contains("*"))) {
    throw new IllegalStateException("oms.gateway.cors.allowed-origins must list exact origins; wildcards are not allowed");
}
// ... allowed methods and headers, c.setAllowCredentials(false) ...
```

A wildcard fails startup (`CorsTest` in the gateway proves it), the default is an empty list meaning same-origin only, and `allowCredentials` is false. The Angular UI is served same-origin (nginx or the dev proxy forwards `/api`), so the UI needs no CORS at all. What CORS is and is not: it is a browser rule that relaxes the same-origin policy for reading cross-origin responses; it does not stop curl, and it is not authentication. A pre-flight `OPTIONS` request must be permitted without a token (`permitAll` for OPTIONS in the edge chain).

CSRF is disabled on purpose, in both chains, and the Javadoc explains why: the API is stateless, never uses cookies or sessions, and authenticates only through the `Authorization: Bearer` header, which browsers do not attach automatically, so there is no ambient credential for a cross-site request to ride. The correct statement for an interview is conditional: disabling CSRF is safe only while authentication is by explicit header. If you move to cookie-based sessions or a BFF that stores tokens in HttpOnly cookies (the production recommendation for browser apps, below), you must re-enable CSRF protection (a synchronizer or double-submit token, `SameSite` cookies). Never disable it "to make a POST work".

Rate limiting exists at two layers. The gateway has an in-memory token bucket per key (`gateway/src/main/java/com/enterprise/oms/gateway/ratelimit/InMemoryTokenBucketLimiter.java`: capacity 100, refill 50 per second by default in `gateway/src/main/resources/application.yml`); its Javadoc states the limit is per instance, so with N replicas the effective limit is N times capacity, which is why a Redis-backed bucket store is available when `oms.redis.enabled=true`. Order-service limits `POST /orders` per caller identity:

```java
CurrentUser user = CurrentUser.require();
if (rateLimitProps.isEnabled()) {
    OrderRateLimiter.Decision d = rateLimiter.tryAcquire(user.id());
    if (!d.allowed()) {
        throw new OrderRateLimitedException(d.retryAfterSeconds());
    }
}
```

Exceeding it yields 429 with `Retry-After`. The dev login endpoint has its own brute-force protection (`LoginThrottle`: failures counted per ip-and-username and per ip inside a sliding window, then a lockout that refuses even the correct password), and its failure message is identical for wrong user and wrong password.

### Example 2: service-to-service identity, and the real OAuth2 flows

Order-service calls inventory for synchronous validation. It authenticates with a short-lived SERVICE JWT (`ServiceTokenProvider`, `services/platform-common/src/main/java/com/enterprise/oms/common/security/ServiceTokenProvider.java`): subject `svc-<service>`, roles `[SERVICE]`, cached and renewed 20 seconds before expiry, default lifetime 2 minutes (`serviceTokenTtl`). `OmsRestClientFactory.createServiceClient` adds the bearer header and the correlation id. It is the same HS256 secret as everything else, which means any holder of the secret can mint any identity, including ADMIN. ADR-0010 and the threat model say plainly that this is acceptable for a demo only. Production replacements, in increasing strength: the OAuth2 **client credentials** grant (each service has its own client id and secret or private-key JWT at the IdP, and receives a token with its own `scope` or role, audited and revocable per client); projected Kubernetes service-account tokens validated by the receiver; or mutual TLS with SPIFFE identities from a mesh. The existing Kubernetes NetworkPolicies (`infrastructure/kubernetes/base/networkpolicies.yaml`) already restrict east-west reachability to gateway to services and order to inventory, which complements, but does not replace, token-based identity.

How the HS256 demo maps to the real thing. Switching the same services to OIDC is a configuration change, by design. In the decoder bean, if `oms.security.jwt.issuer-uri` or `jwk-set-uri` is set, the code builds `NimbusJwtDecoder.withJwkSetUri(...)` or `JwtDecoders.fromIssuerLocation(...)` instead; the HS256 beans (`JwtSecret`, `JwtTokenFactory`, `ServiceTokenProvider`) are not created (the `Hs256ModeCondition`). Compare the models:

| Aspect | HS256 (demo here) | RS256 with JWKS (production) |
|---|---|---|
| Key | one shared secret known to every verifier | IdP holds the private key; services fetch public keys |
| Who can mint tokens | anyone with the secret | only the IdP |
| Rotation | coordinated restart, old tokens invalid | publish new key in JWKS, overlap for one token lifetime |
| Verification | `NimbusJwtDecoder.withSecretKey` | `withJwkSetUri`, keys cached, selected by `kid` |
| Discovery | none | OIDC discovery at `<issuer>/.well-known/openid-configuration` gives `jwks_uri` |

The gateway code path for OIDC is marked "not exercised by the tests; see README". Say the same in an interview: the switch is designed and compiles, not proven against a real IdP. `docs/security/threat-model.md` section 9 lists the migration steps (introduce an IdP, set the two properties, make the gateway a pure resource server, drop the dev token endpoint, replace service tokens with client credentials or mTLS, delete `OMS_JWT_SECRET`).

The flows you should be able to draw, all with the IdP rather than with this repo's dev endpoint:

* **Authorization code with PKCE** (browser SPA or mobile app): the app generates a random `code_verifier` and sends its SHA-256 hash as `code_challenge` with the redirect to the IdP's `/authorize`; the user logs in at the IdP (password, MFA); the IdP redirects back with a short-lived `code`; the app posts the code and the original `code_verifier` to `/token` and receives an access token, an ID token (identity, OIDC) and optionally a refresh token. PKCE binds the code to the app that started the flow, so a stolen code is useless without the verifier, and it removes the need for a client secret in a public client. The implicit flow and the resource-owner password grant are deprecated and should not be used.
* **Client credentials** (machine to machine): the service authenticates to `/token` with its own credentials and gets an access token for itself; no user involved.
* **Refresh tokens**: long-lived, stored securely, rotated on use; absent from this demo (its tokens last 3,600 seconds and then the UI logs out).
* **Browser token storage**: the Angular app keeps the token in a signal mirrored to `sessionStorage` (`core/auth/auth.store.ts`), readable by any script that gets in through XSS; the file's own comment recommends authorization code plus PKCE through a **backend-for-frontend** that holds tokens in HttpOnly, Secure, SameSite cookies, plus a strict CSP. The CSP in `frontend/oms-ui/nginx.conf` (`script-src 'self'`, no inline scripts) reduces but does not eliminate the risk. This is the one place where the demo trades security for simplicity and says so.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Servlet resource-server chain | `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/JwtSecurityAutoConfiguration.java` | stateless, deny by default, HS256 or OIDC mode, method security |
| Reactive edge chain | `gateway/src/main/java/com/enterprise/oms/gateway/security/EdgeSecurityConfig.java` | iss/aud/exp/sub validation, route role rules, CSP and security headers, CORS |
| Secret handling | `services/platform-common/src/main/java/com/enterprise/oms/common/security/JwtSecret.java` | fail fast, min 32 bytes, redacted `toString` |
| Token minting (demo, tests, service) | `.../security/JwtTokenFactory.java`, `.../security/ServiceTokenProvider.java`, `gateway/.../auth/TokenController.java` | HS256; dev endpoint only in profile `dev` |
| Role rules per service | `services/order-service/.../config/OrderSecurityConfig.java`, `@PreAuthorize` on controllers | URL rules before binding plus method security |
| Ownership checks | `.../security/CurrentUser.java`, `services/order-service/.../service/OrderQueryService.java`, `OrderCommandService.java` | 404 for other customers, forced list filter, 403 on create mismatch |
| Rate limits | `gateway/.../ratelimit/*`, `services/order-service/.../ratelimit/*`, `gateway/.../auth/LoginThrottle.java` | in-memory default, Redis optional |
| Header sanitising, internal path block | `gateway/.../filter/EdgeFilters.java` | strips `x-user-*` style headers, 404 for `/api/v1/internal` |
| Security tests | `gateway/src/test/java/com/enterprise/oms/gateway/EdgeSecurityTest.java`, `CorsTest.java`, `RateLimitTest.java`, `TokenThrottleTest.java`; `services/platform-common/src/test/java/com/enterprise/oms/common/security/*` | forged tokens, wildcard CORS, throttling, fail-fast secret |
| Threat model and demo-vs-prod table | `docs/security/threat-model.md`, `docs/security/secure-coding-guide.md` | verification legend marks what is code, tested or static |
| Kubernetes secret handling | `infrastructure/kubernetes/base/order-service.yaml` (`secretKeyRef`), `infrastructure/kubernetes/examples/externalsecret.example.yaml` | no Secret rendered by any overlay |
| UI auth | `frontend/oms-ui/src/app/core/auth/auth.store.ts`, `guards.ts`, `core/http/interceptors.ts` | token never sent to foreign origins or the token endpoint |

### Demonstration-only vs production-grade

Production-grade practices present: signature algorithm pinned, issuer, audience, expiry and required-claim validation; deny-by-default authorization; roles whitelisted from the token; ownership checks with 404 on foreign objects; defence in depth (edge plus service plus method layers); fail-fast secrets with no defaults in prod; stateless sessions; security headers; exact-origin CORS; header sanitising at the edge; two layers of rate limiting; brute-force throttling; RFC 7807 errors that never echo internals; logs that mask emails and never print tokens.

Demonstration-only (listed in `docs/security/threat-model.md` section 8): HS256 with one shared secret known to every service and the gateway; a dev token endpoint with demo users whose password comes from `OMS_DEMO_PASSWORD`; SERVICE tokens minted from that same secret (so any service can impersonate ADMIN); the token in `sessionStorage`; no refresh tokens, no revocation or `jti` deny list (a leaked token is valid until `exp`); no MFA; the OIDC/JWKS path is implemented but not exercised against a real identity provider; actuator and API docs are open on the service port; Kafka is plaintext; the NetworkPolicies were never applied to a cluster; per-instance rate limits unless Redis is enabled; HS256 rotation requires a coordinated restart because dual-key verification (`previous-secret`) is described as planned and not implemented. Also noted in the threat model: `base/configmap.yaml` once defaulted to a `dev` profile (now secure by default, each overlay sets its own) and `/swagger-ui/**` is `permitAll` in the shared chain.

## Common mistakes and gotchas

1. **Trusting the client-supplied identity.** `customerId` in a body or query is attacker-controlled. Take identity from the verified token.
2. **Role check without ownership check (BOLA).** `hasRole('CUSTOMER')` lets every customer into every order. Filter by owner and return 404.
3. **403 for foreign objects.** It confirms the object exists. Use 404 when existence is sensitive.
4. **`hasRole` versus `hasAuthority`.** `hasRole('ADMIN')` looks for `ROLE_ADMIN`; the converter must add the prefix.
5. **Not pinning the algorithm, or skipping `iss`/`aud`/`exp` validation.** Decoding without validating accepts tokens from other systems.
6. **Short or default HMAC secrets.** HS256 needs at least 256 bits of entropy; never commit one; fail startup when absent.
7. **Disabling CSRF while using cookie authentication.** The reasoning "stateless bearer header" only holds without cookies.
8. **Wildcard CORS with credentials, or using CORS as security.** Use exact origins; CORS does not protect the API from non-browser clients.
9. **Putting tokens in `localStorage`, URLs or logs.** Prefer HttpOnly cookies via a BFF; never log `Authorization`.
10. **Trusting gateway headers in backends.** Re-validate the JWT in every service; a misrouted network path bypasses the gateway.
11. **Method security on self-invoked methods.** The AOP proxy is bypassed; secure the entry points and test them.
12. **Using the same token and secret everywhere.** One leak means total compromise; use per-client credentials and RS256 so verifiers cannot mint.
13. **Long-lived access tokens without revocation.** JWTs cannot be recalled; keep them short, use refresh tokens, and add introspection or a `jti` deny list for high-risk systems.
14. **Treating Angular route guards as access control.** They only hide screens; the server enforces.
15. **Per-instance rate limits under autoscaling.** N replicas multiply the limit; use a shared store or the edge.

## Quick-recall cheat sheet

* Filter chain: bearer filter decodes, authorization filter matches rules in order; `anyRequest().authenticated()` last; `STATELESS`; lambda DSL; `@EnableMethodSecurity` with `@PreAuthorize`.
* This repo: HS256 by default (`OMS_JWT_SECRET`, at least 32 bytes, fail fast); switch to RS256 by setting `oms.security.jwt.issuer-uri` or `jwk-set-uri`.
* Validate: signature (pinned algorithm), `exp`/`nbf` with 30 s skew, `iss=oms-dev-issuer`, `aud=oms-api`, required `exp` and `sub` at the edge.
* Roles claim to `ROLE_*` authorities, only CUSTOMER, OPERATOR, ADMIN, SERVICE honoured.
* BOLA defence: identity from token, `canAccessCustomer`, 404 for foreign objects, forced list filter, 403 when body `customerId` differs.
* Service-to-service: SERVICE JWT, 2-minute TTL, sub `svc-<name>`; `/api/v1/internal/**` needs SERVICE and is never routed by the gateway. Production: client credentials, projected SA tokens or mTLS.
* CSRF off only because no cookies; CORS exact origins, no wildcard, same-origin UI needs none.
* Rate limit: gateway token bucket per instance, order-service per caller, 429 plus `Retry-After`, Redis optional.
* Flows: auth code plus PKCE (browser, mobile), client credentials (M2M), refresh token rotation; implicit and password grants are out.
* Demo-only: dev token endpoint, shared secret, `sessionStorage` token, no revocation, OIDC path not exercised against a real IdP.
* Secrets: env or secret manager only; Kubernetes `secretKeyRef`; no Secret objects in overlays; rotation story is described, dual-key is not implemented.
