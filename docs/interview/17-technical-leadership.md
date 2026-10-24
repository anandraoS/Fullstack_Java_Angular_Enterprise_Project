# Technical Leadership and Ownership

Senior interviews stop being about syntax and start being about judgement: how you plan work with imperfect information, how you keep quality high without becoming a bottleneck, how you grow other engineers, how you behave at 3 a.m. when production is on fire, and how you tell the truth to people who would prefer good news. This chapter uses the governance and people-process artifacts that live next to the code: `docs/governance/` (sprint plan, Definition of Ready and Done, risk register, code review checklist, post-incident template, test strategy, deployment and rollback), `docs/adr/`, `docs/mentoring/`, `docs/code-review/`, `docs/runbooks/` and `ai-engineering/`. Treat them as a worked example of a lead's operating system. Where the repository describes a *plan* (the four-sprint schedule, the rehearsals, the retention jobs), say so: much of this is a documented intention for a team that does not exist, not a record of delivery history. A strong answer separates "what the artifact says" from "what I would measure".

## Big picture and kid-friendly analogy

Imagine you are the captain of a school soccer team, and also the one who helps build the team's clubhouse.

* A captain does not score every goal. The captain makes sure everybody knows the **plan for the match** (sprint planning), agrees on **what counts as a goal** (Definition of Done) and on **what a player needs before stepping onto the field** (Definition of Ready).
* Before a new play is used, a teammate **watches the practice run and says what they see** (code review). A good teammate says "the left side is open" and not "you are a bad player". They check the same list every time (a checklist) so they do not forget the boring things.
* The captain **teaches the younger kids** by playing with them and talking through each move out loud (mentoring), not by taking the ball away.
* The team keeps a **notebook of "big decisions and why"** (architecture decision records) so a new kid does not ask "why do we always pass left?" and break the strategy by accident.
* The captain keeps a **list of what might go wrong** (rain, injuries, a broken goal post), with how likely it is and who watches it (risk register).
* When something goes wrong in a match, the team does a **calm replay afterwards** that asks "what in our plan let this happen?" and never "whose fault was it?" (blameless review).
* The captain **guesses how long** things take (estimation), is honest when the guess was off, and tells the coach the truth about the score, even when it is not great (stakeholder communication).
* An old, wobbly goal post you keep postponing fixing is **technical debt**: cheap to ignore today, expensive the day it falls on someone.

| Analogy | Technical concept |
|---|---|
| Plan for the match | Sprint plan with goal, demo and exit gate |
| What counts as a goal / ready to play | Definition of Done / Definition of Ready |
| Teammate watches practice | Code review with a checklist |
| Playing with the younger kids | Mentoring through pairing and review dialogues |
| Notebook of decisions | ADRs (`docs/adr/`) |
| List of what might go wrong | Risk register with likelihood, impact, owner, trigger |
| Calm replay without blame | Post-incident review |
| Guessing how long | Estimation with capacity and buffer |
| Telling the coach the truth | Honest status reporting |
| Wobbly goal post | Technical debt register |

## Beginner tier

### Example 1: agreements that make delivery predictable (sprint plan, DoR, DoD)

`docs/governance/sprint-plan.md` lays out four two-week sprints with a goal, a demo script, epics, stories with points, hours, task breakdowns, acceptance criteria and dependencies. Sprint 1's goal reads "A customer can create an order idempotently; the order is validated against inventory and stock is reserved atomically; every state change writes history, audit and an outbox event in one transaction", and its demo is a runnable sequence ("`POST /api/v1/orders` twice with the same key (replay shows `Idempotent-Replay: true`); order SKU-1019 qty 5 (stock 3) -> REJECTED"). A goal that can be demonstrated is a goal that can be checked.

The two team agreements are in `docs/governance/definition-of-ready-and-done.md`. DoR protects the sprint from vague work; DoD protects the product from half-finished work. Excerpts:

```
## Definition of Ready (story level)
- [ ] Acceptance criteria are testable, written as observable behaviour with HTTP status/problem `code` where relevant.
- [ ] Security impact assessed: who may call it, ownership rule (`CurrentUser.canAccessCustomer`), data sensitivity, threat-model row affected.
- [ ] Estimate agreed (points; 1 point is about 5 focused hours); stories above 8 points are split.
- [ ] Test approach noted: which level (unit/API/integration/e2e) and whether it needs Docker (CI-only verification must be explicit).

## Definition of Done (story level)
- [ ] All suites green locally where runnable; tests that were skipped (for example Testcontainers without Docker) are listed in the PR and are green in CI before the story moves to Done, or the story is marked "Done - CI verification pending" and is not counted.
```

Lead-level observations. The DoD has four levels (story, sprint, release, documentation) so "done" means different things at different gates. It makes honesty a criterion: "Honest status recorded: what is verified, what is only statically validated, what is pending." It refuses partial credit ("unfinished stories return to backlog with re-estimate (no partial points)"), which keeps velocity truthful. And it embeds security and operability (observability considered, runbook updated) into the definition rather than leaving them for a hardening phase.

### Example 2: code review as a culture, with severity, checklists and a stated method

Two artifacts set the tone. `docs/governance/code-review-checklist.md` has gate questions that apply to every pull request ("PR description states what changed, why, and how it was verified, including what was NOT run", "Change is small enough to review in 30 minutes, or split") and then eleven topical sections (contract, security, data and migrations, messaging and saga, reliability, observability, tests, frontend, infrastructure, maintainability). It ends with a merge policy: "Blocker (must fix): security/authz, data loss, migration safety, contract break. Major: missing tests, unbounded work, wrong transaction boundary. Minor/nit: naming and style. Approve only when all blockers and majors are resolved or consciously deferred with a tracked item." `docs/code-review/README.md` adds a severity scale and a six-question method that applies to any diff:

```
1. Read the tests before the code: do they prove the claimed behaviour, or only the happy path?
2. For every write: which transaction, what happens on retry, what happens if two run at once?
3. For every endpoint: who may call, which object, what do they get if not allowed?
4. For every message: is it published after commit, consumed idempotently, and acknowledged only after success?
5. For every query: how many rows, how many statements, which index?
6. For every log line: could it contain a credential or personal data?
```

Seven realistic pull-request scenarios (`docs/code-review/01-oversell-race.md` to `07-pii-in-logs.md`) each contain the diff, the reasoning, severity, the fix and a regression test. Culture points to make: authors self-review first; reviewers comment on the code and the risk, not the person; severity labels stop a nit blocking a release and a blocker being waved through; a checklist is a memory aid, not a replacement for thinking; small pull requests are a quality control; and the review must say what *was not run*. The scenario diffs are "reconstructed teaching examples" with placeholder class names.

## Intermediate tier

### Example 1: mentoring juniors with concrete conversations

`docs/mentoring/` contains six guides written as dialogues between a Reviewer and a Junior (SOLID, design patterns, testing, security, performance, debugging), each anchored in a real design decision of this platform and ending with a takeaway checklist. Look at how the reviewer teaches in the security guide's first dialogue, the BOLA case:

```
**Junior:** The gateway validates the JWT.

**Reviewer:** The gateway proves who you are. It does not know your business rule "CUSTOMER may create orders only for own customerId". The contract says the backend is authoritative.
...
**Junior:** Different status for POST (403) and GET (404)?

**Reviewer:** On create the caller supplied the target, so there is nothing to hide; revealing a refusal is fine. On read the existence of the object is the secret.
```

The technique is transferable. The reviewer *names the principle* (authentication versus authorization), *anticipates the objection* (the junior asks a good question and gets a real answer), *links the rule to the contract* so the junior learns where truth lives, and ends with a one-line takeaway the junior can reuse. Other concrete teaching moments from the same folder: in the testing guide, a junior's oversell test calls `reserve` twice sequentially, and the reviewer explains that "Sequential calls cannot fail on a race" and that `<= 3` "passes if nothing was reserved", then shows a latch-based test with exact assertions; in the same guide `Thread.sleep(3000)` becomes `Awaits.until(...)` with the reasoning "Three seconds is both too long on a fast laptop and too short on a loaded CI runner"; in the performance guide a `SELECT ... FOR UPDATE` reservation is explained as correct but needlessly serialising, with the atomic conditional `UPDATE` as the alternative and a benchmark-both rule ("Default atomic, benchmark both, decide with data"); in the SOLID guide a 600-line `OrderService` is split by counting "the reasons this class would change".

Practical mentoring methods supported by the repo. **Guided discovery**: the code-review guides say "try to spot the problem in the Before snippet before the Reviewer does". **Practice with consequences**: every runbook includes a practice exercise that reproduces the failure on the local stack. **Safe failure**: the `ai-engineering/flawed-ai-examples/` library lets a junior review deliberately bad code without risking production. **Pairing**: the risk register mitigates key-person risk R-19 with "pairing in Sprint 1". A lead's measure of success is that the junior's next pull request contains the lesson without being reminded; note it down and praise it specifically.

### Example 2: architecture decision records keep reasoning alive

Twelve ADRs in `docs/adr/` follow a MADR-style template (`docs/adr/0000-template.md`): status, date, deciders, context, decision drivers, considered options, decision outcome with the concrete mechanism, consequences (good and bad), alternatives rejected, links. Two rules in `docs/adr/README.md` are worth quoting: "Never edit an accepted ADR's decision; supersede it with a new one and update the status line", and a suggested reading order "for interviews or onboarding: 0001, 0004, 0002, 0009, 0005, 0003, 0006, then the rest".

```
## Decision outcome

Chosen option and the one-paragraph justification. State the concrete mechanism (classes, tables, config keys) so a reader can find it in the repo.

### Consequences

* Good: ...
* Bad: ...
```

What makes these useful rather than ceremonial: ADR-0001 opens with honest framing ("a team of this size, with this traffic, would normally ship a modular monolith first. This repository deliberately chooses services because the learning and demonstration goals need the distributed failure modes to be real"); ADR-0006 names real alternatives and the cost it accepts ("H2 `MODE=PostgreSQL` row-lock behaviour differs slightly from PostgreSQL, so contention tests that matter need Testcontainers"); ADR-0008 lists "Bad" consequences including "two CI definitions can drift" and "the deploy job is a placeholder". Leadership behaviours around ADRs: write one when a decision is hard to reverse or will be questioned later; record rejected options so nobody re-litigates them in six months; keep the template short enough that people actually fill it in; link to code; and review ADRs in the same pull request as the change.

### Example 3: estimation with capacity, buffers and honesty

The sprint plan states its planning assumptions in one block, which is exactly how a lead should present an estimate:

```
* Team: 4 engineers (2 backend, 1 full-stack/frontend, 1 platform/QA), 10 working days per sprint, focus factor 0.7 => about 224 productive hours, about 40 story points per sprint.
* Estimation scale: Fibonacci points; **1 point is roughly 5 hours** of focused work including tests and review.
```

Check the arithmetic, as an interviewer will: 4 engineers times 10 days times 8 hours is 320; times 0.7 is 224 productive hours; at 5 hours per point that would be about 45 points, and the plan commits to 40 (200 hours), leaving roughly 24 hours of slack. Each sprint also reserves explicit buffer (for example "plus 3 points (15 h) reserved for Sprint 1 spillover and defects") and the risk register names the weak spot: R-20 "Estimates optimistic because 5 of 8 modules are not yet implemented", with the mitigation "Buffer points in each sprint; re-baseline after Sprint 1 velocity; cut scope in this order: KEDA (S11.3), rate-limit Redis impl, notification retry UI", and the trigger "Sprint 1 completes < 30 of 40 points". That is estimation done well: ranges and assumptions stated, a re-baseline moment scheduled, a scope-cut order agreed in advance, and a measurable trigger.

Techniques to discuss: decompose until stories are at most 8 points (the DoR rule); estimate in relative size rather than hours; separate effort from duration (focus factor); use ranges for uncertain work; track actual versus estimate and recalibrate; never promise a date from a point estimate without a buffer; and say "I do not know yet, here is the spike that will tell us" when it is true.

## Advanced tier

### Example 1: risk management you can actually run

`docs/governance/risk-register.md` has 20 risks scored Likelihood times Impact (1 to 5 each), each with a mitigation, an owner by role (not by name, so ownership survives staffing changes), and a *trigger*, the observable signal that the risk is materialising. A representative row:

```
| R-04 | Portability gap: SQL works on H2 but fails/behaves differently on PostgreSQL 16 | 4 | 4 | 16 | Same Flyway SQL for both; Testcontainers ITs (`AbstractPostgresIT`) run in CI; **skipped on the dev machine (no Docker)** so local green does not prove PG compatibility | Platform/QA | Flyway failure on first PG deploy; CI IT job skipped rather than run |
```

Process around it: scores of 15 or more (R-01, R-02, R-03, R-04, R-06, R-08, R-11, R-15) are "reviewed weekly"; the register is reviewed at each sprint review; risks map to stories (Sprint 1 lists "R-01, R-04, R-09", Sprint 4 lists "R-11, R-12, R-14, R-15"); a materialised risk becomes an incident only with customer-visible impact; and new risks discovered in a post-incident review are added to the register. Lead-level insight: several risks are about *process honesty*, not code: R-11 (infrastructure never applied to a real cluster), R-17 (E2E suite gives false confidence because it runs against a mock backend), R-19 (key-person concentration). Naming such risks publicly is a sign of a trustworthy team.

### Example 2: incident command and the blameless review

The on-call basics in `docs/runbooks/README.md` are a compact incident-management policy:

```
1. **Acknowledge** the page; state who has it.
2. **Assess impact** before diagnosing: which services, since when, how many orders (`oms.saga.active`, `http.server.requests` 5xx ratio).
3. **Stabilise before fixing:** roll back the last deploy, scale, or pause the traffic source; do not hot-fix in production.
4. **Collect evidence before restarting:** thread dump, heap dump if memory, `ops/messaging` output, one correlationId trace. A restart destroys evidence.
5. **Communicate:** one channel, timestamps, hypotheses and actions. Never paste tokens, passwords, or customer emails into the channel; use ids.
6. **Do not edit data by hand** if an admin endpoint exists (retry, reconciliation); those write audit rows (`/api/v1/ops/audit`).
7. **Close with a postmortem** (blameless): timeline, root cause, what detected it, what should have, action items with owners and dates.
```

Severity levels (SEV1 to SEV4) set the response and update cadence (SEV1: page immediately with an incident commander, update every 15 minutes; SEV2: begin within 15 minutes, every 30), with escalation rules (customer data exposure also notifies security; not understood after 30 minutes; two services affected). `docs/governance/deployment-and-rollback.md` supplies the rollback decision tree and a printable backout checklist ("Freeze further deploys; announce in the incident channel", "Capture evidence", "Decide A/B/C ... record the decision and who approved", "Open post-incident review within 2 working days"). The roles are explicit: incident commander, scribe, reviewers.

The review template (`docs/governance/post-incident-review-template.md`) states its philosophy in the first paragraph: "learn and improve, not assign fault ... never write 'X should have known'. People acted reasonably with the information they had." It requires facts-only timelines (UTC, with source), time to detect, mitigate and resolve, a five-whys that stops "when you reach a process, design or tooling gap you can change", contributing-factor checkboxes, a "what went well / what was lucky / what went poorly" section, and action items with these rules: "every action has one owner and a date; at least one action must reduce the chance of recurrence (prevent) and one must reduce time to detect or mitigate; no action reads 'be more careful'." The fictional sample (clearly labelled "FICTIONAL - NOT A REAL INCIDENT") shows the chain of reasoning on a rolling-update incident, ending with the sort of root cause a lead should reach:

```
3. The field was made mandatory in the consumer in the same release that added it to the producer.
4. The contract rule "additive-only; consumers are tolerant readers" is in the contract doc but not enforced by any test or schema check.
5. JSON Schemas in `specs/events` were not validated in CI, and the review checklist item was not applied because the change looked like an internal refactor.
```

Note how it lands on a missing automated control and a process gap, not on a person, and the actions that follow are preventive (a consumer contract test), detective (an alert on dead-letter growth) and procedural (a rollout-order rule).

### Example 3: managing technical debt, stakeholder communication, hiring and onboarding

**Technical debt.** The repository's habit is to write debt down where it can be seen, which is the first step in managing it. Every chapter-level document contains a "demonstration-only versus production-grade" list; ADRs have "Bad" consequences; `docs/_todo-link-check.md` is a ledger of references that do not yet resolve; and the risk register converts debt into scored risk (R-12 "Unbounded growth of `outbox_events`, `processed_events`, `audit_events`" is debt with a trigger and a planned story, S10.4). Sprint 4 includes explicit debt stories (S10.3 "pin Actions to SHA, digest-pin base images", S10.4 retention jobs, S11.4 documentation). A practical policy a lead can propose, clearly labelled as a proposal and not as repository content: keep a visible debt register tied to the risk score; classify debt as deliberate or accidental and prudent or reckless; reserve a fixed share of each sprint (commonly 10 to 20 percent) for paydown; fix debt when you touch the area (the "boy scout" rule); never let debt that scores 15 or more wait without an owner and a date; and avoid the "big rewrite" unless the register shows the alternative costs more.

**Stakeholder communication.** Match the message to the audience and be honest about uncertainty. The artifacts model this: each sprint has a runnable demo; the release-level DoD requires "Release notes list known limitations and unverified areas"; documents carry an honest status section (the deployment guide begins "0. Honest status: ... Nothing has been applied to a cluster"); incidents have update cadences and a customer message rule ("orders are accepted asynchronously (202) and processed when dependencies return; do not tell customers orders are lost"); and executives get a short summary on SEV1 reviews. A useful structure for any update: situation, impact in business terms, what we are doing, what we need from you, next update time. Bad news should travel early and with a plan. Never trade accuracy for comfort, and use order ids rather than customer data in shared channels.

**Hiring and onboarding.** The repository is itself an onboarding kit. A new engineer can follow the ADR reading order (0001, 0004, 0002, 0009, 0005, 0003, 0006), read the contract (`docs/architecture/00-shared-contract.md`) as the single source of truth, run the local stack without Docker (`scripts/run-local.sh`), work through runbook practice exercises, read the mentoring dialogues, and review the flawed examples. A first-week plan built from these (proposal): day 1 environment and a green local test run; days 2 to 3 read ADRs and trace one order through the saga using the correlation id; day 4 review a prepared flawed pull request using the checklist; day 5 pair on a small story that meets the DoR. For hiring, the same material converts into exercises (proposal): give candidates a code-review scenario such as `docs/code-review/01-oversell-race.md` and ask what they would block and why; ask them to explain idempotency in three layers using ADR-0005; or hand them a flawed AI example and ask for a corrected version plus a regression test. Evaluate reasoning, communication and honesty about uncertainty over trivia.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Agile delivery plan | `docs/governance/sprint-plan.md` | 4 sprints, 154 points in total (40, 40, 38, 36) |
| Definition of Ready / Done | `docs/governance/definition-of-ready-and-done.md` | Story, sprint, release, documentation levels |
| Code review | `docs/governance/code-review-checklist.md`, `docs/code-review/*.md` | Blocker/major/minor policy, 7 scenarios |
| Mentoring | `docs/mentoring/*.md` | 6 dialogue guides with takeaway checklists |
| Decisions | `docs/adr/` (ADR 0001 to 0012), `docs/adr/0000-template.md` | Supersede, never edit |
| Risk management | `docs/governance/risk-register.md` | 20 risks, scored, owners and triggers |
| Incident management | `docs/runbooks/README.md`, `docs/runbooks/` (runbooks 01 to 08) | Severity guide, on-call basics |
| Blameless review | `docs/governance/post-incident-review-template.md` | Fictional sample included |
| Deployment safety | `docs/governance/deployment-and-rollback.md` | Rollback decision tree, backout checklist |
| Estimation | `docs/governance/sprint-plan.md` (planning assumptions), `risk-register.md` R-20 | Capacity, focus factor, buffer |
| Tech debt ledger | `docs/_todo-link-check.md`, risk register, Sprint 4 stories | Debt made visible |
| Onboarding | `docs/adr/README.md` reading order, `ai-engineering/README.md` quick start | Local run without Docker |
| AI-assisted workflow | `ai-engineering/` | See chapter 16 |

### Demonstration-only vs production-grade

Production-grade practices: testable acceptance criteria and a layered Definition of Done; checklist-driven reviews with severity; blameless reviews with owned, dated actions; ADRs with rejected options and honest consequences; risks with triggers and role owners; explicit scope-cut order; runbooks with practice exercises; an incident policy that stabilises before fixing and protects evidence. Demonstration-only or aspirational: the sprint plan is a *plan* for a hypothetical four-person team (its own header says "change them if the team differs") with no velocity history; the sample post-incident review is fictional; the review scenarios are reconstructed teaching examples; the rollback and migration drills are scheduled in story S11.2 and have not been performed; owners are roles with no real people; ceremonies are described, not evidenced; there is no on-call rota, paging tool or status page; and the team-health metrics a lead would normally watch (cycle time, change failure rate, time to restore) have no data.

## Common mistakes and gotchas

* **Treating the plan as a record.** A schedule with no velocity history is an estimate, not evidence.
* **Heroic leadership.** If you are the only reviewer, you are the bottleneck and the single point of failure; rotate reviews and write decisions down (R-19).
* **Nitpicking instead of risk review.** Spend attention on blockers (authorization, data loss, migrations) before style.
* **Review by checklist alone.** Ticking boxes without reading the tests is theatre; the method starts with "read the tests before the code".
* **Blame in disguise.** "The engineer failed to test" is blame; "no test or schema check enforced the additive-only rule" is a cause you can fix.
* **Actions that cannot be verified.** "Be more careful" is not an action; every item needs an owner, a date and a type (prevent, detect, mitigate, process).
* **Estimating without capacity.** Ignoring focus factor, review time and interruptions guarantees a missed sprint; commit below measured capacity and re-baseline after the first sprint.
* **Partial credit for unfinished stories.** It inflates velocity and hides risk.
* **Editing history.** Changing an accepted ADR instead of superseding it erases the reasoning.
* **Hiding bad news.** Stakeholders forgive a late warning less than a bad number; communicate early with a plan and the next update time.
* **Debt only in people's heads.** If it is not in a register with an owner, it will not be scheduled.
* **Mentoring by rescue.** Taking the keyboard teaches nothing; ask questions, let the junior find the bug, and give a one-line takeaway.

## Quick-recall cheat sheet

* Sprint: goal, runnable demo, stories at most 8 points, capacity = people times days times hours times focus factor, explicit buffer.
* Estimate: relative points, ranges, stated assumptions, re-baseline after the first sprint, scope-cut order agreed in advance.
* DoR gates entry (testable criteria, security and data impact, test approach, estimate); DoD gates exit (tests, CI, migrations, observability, docs, honest status).
* Review: small PRs, self-review first, read tests first, severity (blocker, major, minor), checklist as memory aid, say what was not run.
* Mentoring: name the principle, link to the source of truth, anticipate the objection, end with a takeaway, practise on safe flawed code.
* ADR: context, drivers, options, outcome with mechanism, good and bad consequences, rejected alternatives; supersede, never edit.
* Risk: likelihood times impact, owner by role, observable trigger, weekly review at 15 or more.
* Incident: acknowledge, assess impact, stabilise, collect evidence, communicate with ids not data, no manual data edits, blameless review within two working days.
* PIR: facts-only timeline, five whys to a process or tooling gap, prevent plus detect actions with owner and date.
* Debt: visible register tied to risk score, fixed capacity share, fix when touching, no ownerless high scores.
* Stakeholders: situation, impact, action, ask, next update; bad news early with a plan; honest status always.
* Onboarding: ADR reading order, contract, local run without Docker, runbook exercises, flawed-code reviews.
* Honest status: governance artifacts describe a plan for a hypothetical team; no velocity, rota or drill evidence exists.
