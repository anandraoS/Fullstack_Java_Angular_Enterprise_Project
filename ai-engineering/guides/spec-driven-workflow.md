# Spec-driven workflow

Seven stages. Each produces a reviewable artifact; the next stage starts from that artifact, not from chat history. The
worked example is order cancellation: `../specs/features/order-cancellation.md`. This is a documented walkthrough of
the method; it does not claim an AI agent ran the stages (see `../agents/README.md`).

```
requirements -> design -> implementation -> testing -> security review -> documentation -> release
   spec s1-5     spec s6     code + migr.    AC->tests     threat model       ADR/runbook      checklist
```

| # | Stage | Input | Output / gate | Human decision |
|---|---|---|---|---|
| 1 | Requirements | problem statement, contract | spec sections 1-5: scope, FRs, Given/When/Then ACs | scope and ACs approved |
| 2 | Design | approved ACs, ADRs | spec section 6: API, state table, events, data, sequence, failure behaviour; ADR if a decision is new | design review |
| 3 | Implementation | spec | smallest diff, migration, DTOs, state machine change, listeners; contract updated | code review |
| 4 | Testing | ACs | one test per AC (traceability table), failure shown first for bugs | all ACs green or explicitly pending |
| 5 | Security review | diff + threat model | answers to the ten questions; authz/idempotency/logging tests | security sign-off |
| 6 | Documentation | final behaviour | OpenAPI export, examples, runbook entry, ADR status, release note | docs review |
| 7 | Release | checklists | `release-readiness.md` ticked; rollback owner | go/no-go |

## Rules that make it work
1. ACs are written before code and are the test names. A criterion with no test is "pending", visibly.
2. The spec states what is NOT verified (e.g. Docker-only tests) up front.
3. Design questions found during implementation go back into the spec (section 11) before more code is written.
4. The assistant implements ONE stage at a time with the spec attached; the human approves the gate.
5. The verification record (spec section 12) is filled with real command output counts, never with intentions.

## How an assistant is used at each stage
1. Requirements: "Draft FRs and Given/When/Then ACs for <feature> from contract sections <n>; list ambiguities."
2. Design: "Propose API, state transitions and events for these ACs using existing seams; flag any new decision for an ADR."
3. Implementation: "Implement FR-1..FR-4 per spec section 6. Do not change anything not named. Show the diff summary."
4. Testing: "Generate tests for AC-1..AC-n; map each to a method name; mark which need Docker."
5. Security: "Run the ten security questions against this diff; list findings with severity and a proposed test."
6. Documentation: "Update OpenAPI, `docs/api/examples.md`, and the runbook; list files touched."
7. Release: "Walk `release-readiness.md` and list unchecked items with owners."
(Ready-to-use versions: `../prompts/prompt-library.md`.)

## Demonstration: order cancellation (summary)
* Stage 1: customers and operators can cancel until fulfillment; ACs cover immediate cancel, compensated cancel,
  forbidden/hidden for non-owners, invalid transition (409), idempotent repeat, race with reservation.
* Stage 2: reuses transitions already in contract section 6 and `OrderStateMachine`; adds `OrderCancelled` event handling
  in inventory (release) and notification; no new table.
* Stage 3-4: acceptance criteria mapped to `OrderStateMachineTest` methods that already exist and to the API and saga tests that now exist (`OrderApiTest`, `OrderSagaOrchestratorTest`, `OrderSagaKafkaIT`,
  `SagaRecoveryTest`; see the table in `specs/features/order-cancellation.md`).
* Stage 5: BOLA, replay, race, forged `OrderCancelled` event.
* Stage 6-7: examples (`docs/api/examples.md`), runbook `docs/runbooks/01-orders-stuck-processing.md`, release items.
