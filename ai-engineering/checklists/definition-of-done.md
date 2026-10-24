# Definition of Done (feature level)

A story is Done only when every box is true. Team-level DoR/DoD with sprint context: `docs/governance/definition-of-ready-and-done.md`.

1. Acceptance criteria (Given/When/Then) each map to an automated test that passes in CI.
2. Code reviewed against `docs/governance/code-review-checklist.md`; no unresolved comments.
3. Unit + API tests added; new endpoints have 401/403/404/2xx tests; new consumers have duplicate/poison tests.
4. No new compiler warnings; static analysis available in CI is green (SpotBugs/Checkstyle are NOT configured, so do not claim them).
5. Security review questions (`context/05-security-review.md`) answered; threat model updated if a trust boundary changed.
6. Migrations are forward-only, portable, expand/contract; verified against H2 (PostgreSQL mode); PostgreSQL run
   verified only if Docker was available (otherwise recorded as not verified).
7. Observability: logs carry `correlationId`; metrics emitted; failure modes have a runbook entry or a note why not.
8. API: OpenAPI export updated; additive-only within v1; examples in `docs/api/examples.md` still true.
9. Docs: contract/ADR/runbook/README touched where behaviour changed; user-visible change in release notes.
10. Feature works through the UI path where one exists (manual or Playwright), including the error state.
11. Honest evidence: PR lists commands executed, counts, and what was skipped or not run.
