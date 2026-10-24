# Definition of Ready and Definition of Done - OMS

Shared team agreements. A story that does not meet DoR is not pulled into a sprint; a story that does not meet DoD is not "Done" and its points are not counted. Related: `sprint-plan.md`, `code-review-checklist.md`, `test-strategy.md`.

## Definition of Ready (story level)

- [ ] Business value and user/role stated ("As a CUSTOMER/OPERATOR/ADMIN/SERVICE ... I want ... so that ...").
- [ ] Acceptance criteria are testable, written as observable behaviour with HTTP status/problem `code` where relevant.
- [ ] Contract impact identified: endpoints, roles, events, error codes in `docs/architecture/00-shared-contract.md`; if the contract must change, that is part of the story.
- [ ] Security impact assessed: who may call it, ownership rule (`CurrentUser.canAccessCustomer`), data sensitivity, threat-model row affected.
- [ ] Data impact assessed: migrations (service range `V10+`), backward compatibility (expand/contract), seed needs.
- [ ] Messaging impact assessed: new/changed event, idempotency key, ordering key, DLT behaviour.
- [ ] Dependencies named and either Done or scheduled earlier (see dependency column in sprint plan); external needs (Docker host, cluster access, IdP) confirmed available.
- [ ] Estimate agreed (points; 1 point is about 5 focused hours); stories above 8 points are split.
- [ ] Test approach noted: which level (unit/API/integration/e2e) and whether it needs Docker (CI-only verification must be explicit).
- [ ] UX/design input attached for UI stories (states: loading, empty, error, 401/403/404).
- [ ] Observability considered: metric/log/alert/ops-endpoint signal.

## Definition of Done (story level)

Code
- [ ] Implemented to acceptance criteria; behaviour matches the contract; contract file updated in the same PR when deviating.
- [ ] Peer-reviewed with `code-review-checklist.md`; all blocker and major comments resolved.
- [ ] No secrets, defaults for secrets, real data, or debug backdoors; no new demo-only control reachable under a prod profile.
- [ ] Errors use the shared problem+json handling; no stack traces/SQL to clients; logs masked.

Tests
- [ ] Unit tests for new logic; API/integration tests for new endpoints/consumers including negative cases (401, 403, 404 for foreign resource, 400, 409).
- [ ] Idempotency proven for any consumer/command/endpoint that can be retried.
- [ ] All suites green locally where runnable; tests that were skipped (for example Testcontainers without Docker) are listed in the PR and are green in CI before the story moves to Done, or the story is marked "Done - CI verification pending" and is not counted.
- [ ] Coverage on changed classes not reduced (JaCoCo); frontend specs updated; e2e updated for user-visible flows.

Build and delivery
- [ ] CI pipeline green (build, tests, SAST/CodeQL, Semgrep, dependency scan, gitleaks).
- [ ] Migrations additive and portable (H2 PostgreSQL mode and PostgreSQL 16); rollback path stated in the PR (undo vs fix-forward).
- [ ] Config added to ConfigMap/README; secrets only via `secretKeyRef`; `scripts/validate-infra.sh` passes when infra changed.

Operations
- [ ] Metrics/logs/alerts added or confirmed; dashboards updated when new signals appear.
- [ ] Runbook or ops note updated if an operator can now act on it.

Documentation
- [ ] OpenAPI/event schema exports refreshed (`specs/`), docs and ADR updated where a decision was made.
- [ ] Honest status recorded: what is verified, what is only statically validated, what is pending.

Demo
- [ ] Demonstrated to the product owner in the sprint review on a running stack (local-stack or Compose), or recorded as not demonstrable with the reason.

## Definition of Done (sprint level)

- [ ] Sprint goal met or explicitly renegotiated.
- [ ] All counted stories meet story DoD; unfinished stories return to backlog with re-estimate (no partial points).
- [ ] Main branch builds and all non-Docker tests pass; CI integration jobs green.
- [ ] Risk register and threat-model "pending tests" lists reviewed and updated.
- [ ] Demo done; retrospective held; actions have owners.

## Definition of Done (release level)

- [ ] All sprint-level DoD items for included sprints.
- [ ] PostgreSQL integration tests executed (not skipped) in CI; Playwright live mode green.
- [ ] No unresolved HIGH/CRITICAL scanner findings with an available fix; images scanned and referenced by digest.
- [ ] Deployment rehearsed on a non-prod cluster including `rollout undo` and a migration drill (`deployment-and-rollback.md`).
- [ ] Prod overlay checked: no `dev` profile, no placeholder endpoints, secrets from the secret manager, JWT on RS256/JWKS or an accepted, time-boxed exception.
- [ ] Security review of the threat-model residual risks signed off by the security champion.
- [ ] Release notes list known limitations and unverified areas.

## Definition of Done (documentation and ADR)

- [ ] States verified facts separately from intent; every "pending" item has an entry in `docs/_todo-link-check.md` until it exists.
- [ ] Links resolve to files that exist.
