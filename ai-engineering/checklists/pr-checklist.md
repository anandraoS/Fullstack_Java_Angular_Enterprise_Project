# Pull request checklist

Copy into the PR description. An AI-authored PR must also fill the last section.

## Scope and spec
- [ ] Linked spec (`ai-engineering/specs/features/<name>.md`) or issue; acceptance criteria listed with test names.
- [ ] Diff is the smallest that satisfies the spec; no unrelated refactors; no new dependency (or justified).
- [ ] Contract (`docs/architecture/00-shared-contract.md`) updated in the same PR if behaviour changed.

## Correctness
- [ ] State changes + history + audit + outbox event in one transaction; nothing published to Kafka inside the tx.
- [ ] Concurrency considered: atomic statements/constraints, no check-then-act, sorted lock order.
- [ ] Consumers idempotent; duplicate and out-of-order handled; poison -> DLT, never swallowed.
- [ ] Errors are typed, map to RFC 7807 `code`; no internals in responses.

## Security
- [ ] Authn + role + object-level authorization for each endpoint; identity from token only.
- [ ] No secrets, no PII or tokens in logs/tests/examples; `LogMasking` used.
- [ ] Input bounded and validated; no string-built SQL; sort fields whitelisted; page size capped.
- [ ] `ai-engineering/context/05-security-review.md` ten questions answered.

## API and data
- [ ] Additive-only within v1; OpenAPI export updated; problem `code`s documented.
- [ ] Flyway migration follows `06-db-migrations.md` (version, portable SQL, expand/contract, indexes).
- [ ] Event schema change is additive or versioned; schema file updated.

## Tests
- [ ] New/changed behaviour has tests that fail without the change; names describe behaviour.
- [ ] Security (401/403/404) and idempotency tests for new endpoints/consumers.
- [ ] Commands run and results pasted (counts). Docker-dependent tests reported as SKIPPED if Docker absent.

## Operability and docs
- [ ] Metrics/logs for the new path; alert or runbook note if it can fail silently.
- [ ] Docs/ADR/runbook updated; release notes line drafted.

## AI-authored change disclosure
- [ ] Which parts were generated; which were hand-verified.
- [ ] Every class, endpoint and metric name in the diff was verified by grep, not recalled.
- [ ] The relevant `flawed-ai-examples` were reviewed against the diff (oversell, self-invocation, outbox, idempotency,
      BOLA, logging, pagination/N+1, SQL, catch-and-ack, singleton state).
- [ ] Anything not run or not verified is stated explicitly below.
