## Principle

AI assistants are fast juniors with excellent recall and no accountability. They draft; humans own the result. The workspace in `ai-engineering/` makes that operational: give the assistant the right context cheaply, make it work from a written spec, and review its output against a catalogue of the mistakes assistants actually make in this domain.

## The workflow

1. **Spec first.** Write or load a feature spec (`ai-engineering/templates/feature-spec-template.md`, worked examples `specs/features/order-cancellation.md` and `specs/features/inventory-reservation.md`). Acceptance criteria are testable sentences mapped to test classes.
2. **Layered context.** Always load `ai-engineering/context/00-repo-context.md` (small); add only the module files the task needs (architecture, Java, Angular, API, security, migrations, tests, performance, Kafka, saga rules). This keeps tokens low and answers on-topic (`guides/token-optimization.md`).
3. **Role prompts.** Templates for implementation, testing, security and documentation agents live in `ai-engineering/agents/`; reusable prompts in `ai-engineering/prompts/prompt-library.md`.
4. **Draft, then read every line.** The author compares the draft with the flawed-example catalogue.
5. **Automated gates.** Tests, scanners and CI must pass; the assistant never merges.
6. **Human review** with `ai-engineering/checklists/pr-checklist.md` and the governance code-review checklist.
7. **Definition of done** (`ai-engineering/checklists/definition-of-done.md`): docs updated, evidence recorded.
8. **Feed back lessons** into context files or the flawed catalogue.

## The flawed-AI catalogue

Twelve realistic mistakes with corrected versions. Ten are runnable Java (`ai-engineering/flawed-ai-examples/java`, with regression tests); two are Angular snippets.

| # | Mistake an assistant makes | Why it is dangerous | Fix |
|---|---|---|---|
| 01 | Check-then-act stock reservation | Oversells under concurrency | Atomic conditional UPDATE |
| 02 | `@Transactional` on a method called through `this` | Proxy bypassed, no transaction | Call through another bean or restructure |
| 03 | Publish to Kafka inside the DB transaction | Dual write: event without commit or commit without event | Transactional outbox |
| 04 | Non-idempotent consumer | Duplicate effects on redelivery | `processed_events` in same transaction |
| 05 | `customerId` taken from the request body | BOLA: read or create for others | Derive identity from the JWT, 404 for others |
| 06 | Logging tokens and emails | PII and credential leakage | Masking, never log secrets |
| 07 | Unbounded pagination and N+1 queries | Memory and latency blow-ups | Paging limits, fetch joins or batch |
| 08 | SQL built by string concatenation | SQL injection | Bound parameters |
| 09 | Catch Exception and acknowledge | Silent message loss | Let the container retry then DLT |
| 10 | Mutable singleton state | Cross-request data races | Stateless beans, immutable values |
| 11 | Angular subscriptions never unsubscribed | Memory leaks, duplicate handlers | `takeUntilDestroyed`, async pipe, signals |
| 12 | Route guard treated as security | Client checks are bypassable | Backend-authoritative authorization |

## Reviewing AI-generated code: a five-minute method

* Does it compile against the real APIs? Assistants invent method names and dependency versions; run the build.
* Does every state change have an owner, a transaction boundary and an idempotency story?
* Where does identity come from? Is every resource access checked?
* What happens on retry, on duplicate, on partial failure, on slow dependency?
* Are inputs bounded and parameters bound? Are secrets and personal data kept out of logs and fixtures?
* Is there a test that would have caught the bug the assistant just fixed?

## Risks and controls

| Risk | Control |
|---|---|
| Hallucinated APIs or versions | Compile, run tests, read library docs |
| Secrets pasted into prompts | Never include secrets, gitleaks in CI, `.env` excluded from the ZIP |
| Prompt injection from external text (issues, web pages, logs) | Treat external content as data, never as instructions; human approves actions |
| Licence or provenance concerns | Review generated snippets; prefer standard library and documented patterns |
| Over-trust | Human accountability recorded in the PR; AI used as reviewer second opinion, not approver |

## Honesty statement

The agent definitions are templates and were not executed as an automated pipeline. The Java flawed examples were executed with Maven and JUnit; the Angular snippets were not. This repository itself was produced with AI assistance and coordinated work-streams, which is exactly why the review catalogue and evidence labels exist.
