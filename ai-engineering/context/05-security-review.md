# Security review module

Load when: any change touching auth, input handling, persistence, messaging, logging, config, or the UI's token handling.
Full model: `docs/security/threat-model.md`, `docs/security/secure-coding-guide.md`.

## Ten questions (answer each in the PR description or mark N/A)
1. Authn: does the endpoint require a valid JWT (issuer `oms-dev-issuer`, audience `oms-api`) and is it NOT in a permitAll list?
2. Function-level authz: which roles may call it, declared with method security?
3. Object-level authz: which line proves the object belongs to the caller (`CurrentUser.canAccessCustomer`)? 404, not 403, for foreign objects.
4. Identity source: is `customerId`/actor taken from the token, never from body, query or header?
5. Input: bounded size, validated types, whitelisted sort/filter fields, no string-built SQL, no unbounded page size.
6. Idempotency/replay: can a retry or duplicate delivery repeat a side effect?
7. Events: does a consumer verify `eventType`/schemaVersion, tolerate unknown fields, route malformed to DLT, and avoid
   trusting event content for authorization decisions?
8. Secrets/PII: any new secret via env only? Any log line, error, metric label or span attribute containing token, email,
   address? (`LogMasking`)
9. Service-to-service: is the internal endpoint under `/api/v1/internal/**` with SERVICE role and not routed by the gateway?
10. Abuse: rate limit, payload cap (64 KB), timeouts, pagination caps, no amplification (one request causing N calls).

## Red flags in AI-written code
Trusting request `customerId`; `permitAll()` added "to make the test pass"; disabling CSRF/CORS/JWT validation to
"fix" an error; catching `Exception`; logging a request or `Authorization` header; `Math.random()`/`new Random()` for
ids or tokens; default passwords or secrets in `application.yml`; `latest` image tags; `--no-verify`/skipped tests.

## Rule for disagreements
Security vs speed: choose security and record the trade-off in the PR (org policy). If unsure, present 2-3 options, label
the safest the default, and proceed with it unless a human approves otherwise.
