# ai-engineering: a reusable AI-assisted workspace for this repo

Everything here is plain Markdown. It is written so a coding assistant (Claude Code, Copilot, Cursor, or a human new to
the repo) gets the right context at the lowest token cost, and so AI-written changes are held to the same bar as human
ones. It contains no secrets and no real data.

## Layout

| Path | Purpose |
|---|---|
| `context/00-repo-context.md` | CLAUDE.md-style entry point: what the system is, commands, invariants. Always loaded. |
| `context/01..10-*.md` | Modular rules loaded on demand: architecture, java, angular, api, security, db-migrations, tests, performance, kafka, saga |
| `context/03b-react-standards.md` | Rules for the second UI, `frontend/oms-ui-react` (React 19, Redux Toolkit/RTK Query, MUI, Vitest); loaded instead of 03 for React work |
| `checklists/` | PR checklist, Definition of Done, release readiness |
| `templates/feature-spec-template.md` | Spec skeleton used by `specs/features/*` |
| `specs/features/` | Worked specs: `order-cancellation.md` (full end-to-end), `inventory-reservation.md` |
| `guides/spec-driven-workflow.md` | requirements -> design -> implementation -> tests -> security review -> docs -> release |
| `guides/token-optimization.md` | context layering, @-includes, summarization, when to use subagents |
| `prompts/prompt-library.md` | Tested-in-spirit prompts per task |
| `agents/` | Role/prompt TEMPLATES for implementation, testing, security, documentation agents |
| `flawed-ai-examples/` | 12 flawed AI outputs with corrections and regression tests (10 runnable Java, 37 tests) |

## Honesty statement

* The agent definitions in `agents/` are templates. They were NOT executed as part of building this repo.
* The spec-driven workflow is demonstrated on order cancellation as a documented walkthrough. Acceptance criteria are
  mapped to test classes; each mapping is marked `exists` or `open` as of the last verification (2026-10-18) (see the table at the
  end of `specs/features/order-cancellation.md`).
* The flawed-example Java project was executed (Maven, JUnit 5); the Angular snippets were not. The React standards file (`03b`) was written from the executed React app (2026-10-19) and was not itself run through an assistant session.

## Quick start for an assistant session

1. Load `context/00-repo-context.md` (about 1.5k tokens).
2. Add only the module files that match the task (see the routing table in `guides/token-optimization.md`).
3. Point at one spec in `specs/features/` instead of pasting design discussion.
4. Finish with `checklists/pr-checklist.md`.
