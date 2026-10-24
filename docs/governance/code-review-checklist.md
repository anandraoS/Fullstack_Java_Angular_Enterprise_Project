# Code Review Checklist - OMS

Use for every pull request. Reviewer ticks or comments on each applicable section; "N/A" is fine but say so. Authors self-review first. Background: `docs/architecture/00-shared-contract.md`, `docs/security/secure-coding-guide.md`.

## 0. Gate questions (all PRs)

- [ ] PR description states what changed, why, and how it was verified, including what was NOT run (for example "Testcontainers tests skipped, no Docker locally").
- [ ] Change is small enough to review in 30 minutes, or split.
- [ ] No secrets, tokens, real emails, real customer data, or credentials in code, config, tests, fixtures, logs or screenshots (synthetic `*.example.test` domains only).
- [ ] CI is green, or the failure is explained and unrelated.

## 1. Contract and API

- [ ] Paths, roles, status codes, JSON field names match `00-shared-contract.md`; if not, the contract is edited in the same PR.
- [ ] Changes within `/api/v1` are additive only (no removed/renamed fields, no stricter validation on existing inputs).
- [ ] Errors are thrown as `OmsException` subclasses or mapped through `GlobalExceptionHandler`; no hand-built error JSON, no stack traces, no SQL text in responses.
- [ ] Paging uses `PageRequestFactory` with an explicit sort whitelist; no silent clamping.
- [ ] `Idempotency-Key` handled with `IdempotencyKeys.require` on creating endpoints that need it.
- [ ] OpenAPI export in `specs/openapi` regenerated if endpoints changed (pending directory).

## 2. Security and authorization (blocker-level)

- [ ] Every endpoint has an explicit role rule (method security or `OmsAuthorizeRequestsCustomizer`); nothing relies on the UI hiding a button.
- [ ] Resource-level ownership checked with `CurrentUser.canAccessCustomer(...)`; another customer's resource returns 404 (contract), never 403 or 200.
- [ ] List endpoints force the customer filter for CUSTOMER; the filter value comes from the token, not the query string.
- [ ] `customerId`, role, or actor never taken from the request body when the token carries it.
- [ ] SQL: only parameter binding / Spring Data derived queries / named parameters; no string concatenation into JPQL/SQL; sort fields whitelisted.
- [ ] Logging: no tokens, passwords, full emails; user-controlled values pass `LogMasking.stripNewlines`; free text passes `LogMasking.sanitize`.
- [ ] No new `permitAll`, CSRF/CORS relaxation, disabled filter, or `allow-ephemeral-secret=true` outside tests.
- [ ] `/api/v1/internal/**` stays SERVICE-only and unrouted by the gateway.
- [ ] Secrets only via env/Secret (`OMS_JWT_SECRET`, `OMS_DEMO_PASSWORD`); no defaults added.
- [ ] Outbound HTTP uses `OmsRestClientFactory` (timeouts 2 s connect / 3 s read).

## 3. Data and migrations

- [ ] Flyway file is the next version in the service range (`V10+`, never edit an applied migration, never use V1..V8 outside platform-common, seeds `V1000+`).
- [ ] SQL is portable (runs on PostgreSQL 16 and H2 in PostgreSQL mode): no pg-only types/functions, no `ON CONFLICT` unless proven on both.
- [ ] Expand/contract respected: new columns nullable or defaulted; drops/renames only in a later release after code stopped using them.
- [ ] Indexes for new query predicates; no unbounded `SELECT` without paging.
- [ ] Money is `BigDecimal` scale 2; timestamps are UTC `TIMESTAMP WITH TIME ZONE`.
- [ ] State change + history + audit + outbox event happen in ONE transaction.

## 4. Messaging and saga

- [ ] Events are appended via `OutboxService.append` inside the business transaction; no direct `KafkaTemplate.send` from request handlers.
- [ ] Consumers go through `IdempotentEventProcessor.processOnce`; the effect is inside the callback.
- [ ] Event changes are additive; envelope fields unchanged; key = `orderId`.
- [ ] Errors classified correctly (`NonRetryableEventException` / `MalformedEventException` for poison data; transient errors rethrown for retry).
- [ ] State transitions go through the state machine (`OrderStateMachine`, pending); invalid pair -> `InvalidStateTransitionException`.
- [ ] Re-applying the same event/command is a no-op; a test proves it.
- [ ] No claim of "exactly once" or "ACID across services" in code comments or docs.

## 5. Reliability and performance

- [ ] Timeouts, retries (bounded), circuit breaker on remote calls; no retry of non-idempotent calls.
- [ ] No blocking calls in the reactive gateway.
- [ ] No N+1 queries in list endpoints; batch sizes bounded.
- [ ] Scheduled jobs are safe with multiple replicas (idempotent or guarded) and bounded per run.
- [ ] Graceful shutdown still works (no non-daemon thread leaks, listeners stop).

## 6. Observability

- [ ] Metrics use the contract names (`oms.events.*`, `oms.outbox.pending`, `oms.saga.*`) and low-cardinality tags (never orderId/customerId as a tag).
- [ ] Correlation id preserved in MDC and Kafka headers for new flows.
- [ ] Meaningful log levels; no log in a hot loop; no PII.
- [ ] New failure modes have an alert or an ops-endpoint signal.

## 7. Tests

- [ ] Unit tests for logic; slice/integration test for wiring; behaviour (not implementation) asserted.
- [ ] Negative tests: 401, 403, 404-for-foreign-resource, 400 validation, 409 state, 413 size where relevant.
- [ ] Tests are deterministic: use `Awaits` from platform-test-support, not `Thread.sleep`; secrets generated at runtime (`JwtTestTokens`), none hard-coded.
- [ ] Kafka tests extend `AbstractKafkaIT` (embedded KRaft); PostgreSQL tests extend `AbstractPostgresIT` (Testcontainers, skipped without Docker) and the PR says whether they ran.
- [ ] Coverage did not drop on changed packages (JaCoCo report).
- [ ] Frontend: spec added/updated next to the component (`*.spec.ts`); user-visible flow change updates `tests/e2e/specs`.

## 8. Frontend (Angular and React)

- [ ] React (`frontend/oms-ui-react`): no `dangerouslySetInnerHTML` with data; server data only in RTK Query and client state only in slices; hook dependency arrays honest and timers cleaned up.
- [ ] A behaviour or contract change is mirrored in the other UI (or the gap is stated), both `models.ts` copies are updated, and the shared Playwright specs pass for both (`E2E_UI` angular and react).
- [ ] Angular: no `innerHTML`/`bypassSecurityTrust*` with data; interpolation only.
- [ ] Tokens not written to `localStorage` unless the ADR allows; no tokens in URLs or console logs.
- [ ] Error display never echoes server `detail` for auth failures (contract 5.5); generic message.
- [ ] Route guards are UX only; every guarded action is also enforced server-side.
- [ ] Runtime config via `config.json`, no environment secrets in the bundle.
- [ ] Accessibility basics: labels, focus handling in dialogs, keyboard operation.

## 9. Infrastructure and delivery

- [ ] Manifests: no Secret objects, only `secretKeyRef`; non-root, read-only root FS, dropped capabilities preserved; probes present.
- [ ] `scripts/validate-infra.sh` passes (kustomize build, kubeconform).
- [ ] New env var documented in `configmap.yaml` and README; no secret in ConfigMap.
- [ ] Image references via the Kustomize `images:` block; prod by digest.
- [ ] Rollback considered: can the previous version run against the new schema/events?

## 10. Maintainability

- [ ] Names, packages and layering follow `LLD.md`; no cross-service DB or class imports besides platform-common.
- [ ] No dead code, no commented-out blocks, no TODO without a ticket id.
- [ ] Docs updated (contract, runbook, ADR) where behaviour changed.

## Review outcome

Blocker (must fix): security/authz, data loss, migration safety, contract break. Major: missing tests, unbounded work, wrong transaction boundary. Minor/nit: naming and style. Approve only when all blockers and majors are resolved or consciously deferred with a tracked item.
