# ADR-0010: JWT bearer tokens, OIDC-compatible resource servers, backend-authoritative authorization

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture, security

## Context

The platform needs authentication at the edge, authorization inside each service, service-to-service identity, and a story for production identity providers, without shipping secrets or default passwords.

## Decision drivers

* Defence in depth: gateway validates, every service validates again.
* Authorization must not depend on the UI.
* Swap HS256 demo tokens for a real OIDC provider by configuration only.
* No hard-coded secrets; fail fast when misconfigured.

## Considered options

1. Opaque session cookies with a central session store.
2. Self-contained JWTs validated by every service (chosen).
3. Gateway-only authentication with trusted internal headers.
4. mTLS only for service identity.

## Decision outcome

* Tokens: JWT with claims `sub`, `roles` (`CUSTOMER|OPERATOR|ADMIN|SERVICE`), `customerId`, `iss=oms-dev-issuer`, `aud=oms-api`, `exp`, `iat`, `jti`. Demo mode is HS256 with the secret from `OMS_JWT_SECRET` (at least 32 bytes). There is no default in the prod profile: startup fails if it is missing or short (`JwtSecret`, `OmsSecurityProperties`), unless `oms.security.jwt.allow-ephemeral-secret=true`, which is for tests and single-process runs only. `scripts/run-local.sh` generates a random secret per run.
* OIDC: setting `oms.security.jwt.issuer-uri` or `jwk-set-uri` switches resource servers to RS256 via JWKS; the HS256 beans (`JwtTokenFactory`, `ServiceTokenProvider`) are then not created. Code in `services/platform-common/.../common/security`.
* Layers: the gateway validates the JWT, applies CORS, rate limiting and correlation ids; each service re-validates (never trusts a header from the gateway) and authorizes with method security plus resource-level checks. `/api/v1/internal/**` requires `SERVICE` and is not routed by the gateway.
* Service identity: short-lived SERVICE JWT minted by `ServiceTokenProvider` (`sub=svc-order-service`).
* Authorization matrix lives in contract section 4. Cross-tenant access returns 404, not 403, to prevent enumeration. 401 and 403 bodies are RFC 7807 via `ProblemSecurityHandlers`.
* Dev token endpoint exists only in the gateway `dev` profile; demo passwords come from `OMS_DEMO_PASSWORD`, never from code.
* UI: guards in Angular are UX only (ADR-0012); the token lives in a signal mirrored to `sessionStorage`. Known trade-off: readable by XSS; production should use authorization-code with PKCE through a BFF with HttpOnly cookies, plus strict CSP.

### Consequences

* Good: stateless horizontal scaling, no session store, clear production path (OIDC), tamper-resistant authorization, fail-fast config.
* Bad: JWTs cannot be revoked before `exp` (mitigate with short lifetimes and, in production, introspection or a deny list keyed by `jti`); HS256 shares one secret across all services (any holder can mint tokens; acceptable only for demo, not for production); sessionStorage token exposure to XSS; each service pays JWT verification cost; key rotation for HS256 requires coordinated restart.

## Alternatives rejected

* Server-side sessions: fits browsers but not service-to-service calls and needs a shared store.
* Trust gateway headers: any bypass of the gateway (misrouted network policy) becomes full privilege escalation.
* mTLS only: strong service identity but carries no end-user roles; complementary, not a replacement (the K8s network policies already restrict east-west traffic).

## Links

* Contract section 4, 12; `services/platform-common/src/main/java/com/enterprise/oms/common/security`
* `infrastructure/kubernetes/base/networkpolicies.yaml`; `frontend/oms-ui/src/app/core/auth/auth.store.ts`
* ADR-0012
