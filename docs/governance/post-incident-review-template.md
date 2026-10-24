# Post-Incident Review (PIR) Template - Blameless

Purpose: learn and improve, not assign fault. Describe what the system and the process allowed to happen; never write "X should have known". People acted reasonably with the information they had. Complete within 2 working days of resolution for SEV1/SEV2, within 5 for SEV3. Store as `docs/runbooks/pir/PIR-<yyyy-mm-dd>-<slug>.md`. No secrets, tokens, real customer data or full emails in this document (mask with `LogMasking` conventions, `a***@domain`).

## Severity guide

| Sev | Definition (OMS examples) | Review required |
|---|---|---|
| SEV1 | Orders cannot be created or are being processed incorrectly for most users; data corruption (oversell, wrong status) | Yes, with exec summary |
| SEV2 | Major function degraded: saga stuck for a class of orders, notification backlog, one service down | Yes |
| SEV3 | Minor degradation with workaround, DLT growth, recoverable | Short form |
| Near miss | No customer impact but a control failed | Short form |

## 1. Summary

| Field | Value |
|---|---|
| Title | |
| Incident ID / PIR ID | |
| Severity | |
| Date and duration | start (UTC) / detected (UTC) / mitigated (UTC) / resolved (UTC) |
| Incident commander / scribe / reviewers | roles or names |
| Services and components | e.g. order-service, `OutboxRelay`, topic `oms.inventory.events.v1` |
| Customer impact | who, how many orders, what they saw |
| Data impact | orders affected (ids in appendix), repaired how, any unrecoverable data |
| Detection | alert, dashboard, customer report, manual |

One paragraph in plain language: what happened, how it was mitigated, current state.

## 2. Impact quantification

* Orders created / failed / stuck during the window (query or dashboard link).
* Peak consumer lag, `oms.outbox.pending`, DLT depth, error-rate p95.
* Time to detect (TTD), time to mitigate (TTM), time to resolve (TTR).
* Error budget consumed (if SLOs defined: pending).

## 3. Timeline (UTC; facts only)

| Time | Event | Source (alert, log, chat, deploy) |
|---|---|---|
| hh:mm | Change or trigger | |
| hh:mm | First symptom | |
| hh:mm | Alert fired / customer report | |
| hh:mm | Responder acknowledged | |
| hh:mm | Hypothesis tested and result (include wrong turns) | |
| hh:mm | Mitigation applied | |
| hh:mm | Recovery confirmed | |

## 4. Root cause analysis

### 4.1 What happened (technical narrative)
Trace through the system: request or event, component, failure mode. Include correlation ids and log excerpts (masked).

### 4.2 Five whys
1. Why did the customer-visible problem occur?
2. Why did that happen?
3. Why?
4. Why?
5. Why? (stop when you reach a process, design or tooling gap you can change)

### 4.3 Contributing factors
Check all that apply and explain: [ ] code defect [ ] missing test [ ] migration not backward compatible [ ] configuration/secret [ ] capacity [ ] dependency outage (Kafka/DB/Redis) [ ] missing alert [ ] runbook gap [ ] unclear ownership [ ] demo-only control in production [ ] contract drift.

### 4.4 What went well / what was lucky / what went poorly

## 5. Detection and response review

* Did alerts fire on symptom, not only cause? Were they actionable?
* Could the on-call find the order via correlation id in logs and `/api/v1/ops/audit`?
* Were rollback and fix-forward decisions made using `deployment-and-rollback.md`? What was unclear?

## 6. Action items

| ID | Action | Type (prevent / detect / mitigate / process) | Owner (role) | Priority | Due | Tracking link | Status |
|---|---|---|---|---|---|---|---|
| A-1 | | | | P0/P1/P2 | | | open |

Rules: every action has one owner and a date; at least one action must reduce the chance of recurrence (prevent) and one must reduce time to detect or mitigate; no action reads "be more careful". Risks identified go into `docs/governance/risk-register.md`.

## 7. Lessons learned and follow-up review date

## 8. Appendix
Affected order ids (synthetic ids only in non-prod), queries, dashboards, relevant commits, deploy revision numbers.

---

# SAMPLE (FICTIONAL - NOT A REAL INCIDENT)

> Everything below is invented to illustrate the template. No such incident occurred; the system it describes (`SagaRecoveryJob`, order-service) was not implemented at the time of writing. Dates and counts are made up.

## Summary

| Field | Value |
|---|---|
| Title | Orders stuck in VALIDATED after inventory-service rolling update |
| PIR ID | PIR-2026-11-04-stuck-validated (fictional) |
| Severity | SEV2 |
| Time | start 10:12, detected 10:31, mitigated 10:58, resolved 11:40 (UTC, fictional) |
| Roles | IC: on-call backend; scribe: QA lead |
| Components | inventory-service, topic `oms.inventory.commands.v1`, order-service saga |
| Customer impact | 143 orders (fictional) waited more than 5 minutes in VALIDATED; no data loss |
| Detection | alert "saga active age > 5 min" |

Narrative: a release added a new mandatory field to the `ReserveInventory` payload parser in inventory-service. Order-service pods still on the previous version emitted commands without the field; the new parser threw `IllegalArgumentException`, classified as non-retryable, and 143 commands went to `oms.inventory.commands.v1.DLT`. Orders waited in VALIDATED. Mitigation: rolled inventory-service back (`kubectl rollout undo`), then replayed the DLT records; idempotent consumption meant no double reservation. `SagaRecoveryJob` would also have re-sent the commands after its stuck threshold, which is why no order reached FAILED.

Timeline (fictional): 10:12 inventory rollout begins; 10:14 first DLT record; 10:31 alert fires; 10:35 IC declares SEV2; 10:41 DLT depth 143 found via `/api/v1/ops/messaging`; 10:52 decision: rollback (no migration in release, tree branch A); 10:58 rollback complete; 11:05 DLT replay started; 11:40 all 143 orders progressed.

Five whys (fictional):
1. Orders stuck because inventory never answered `ReserveInventory`.
2. Commands were dead-lettered because the new parser rejected payloads missing a new field.
3. The field was made mandatory in the consumer in the same release that added it to the producer.
4. The contract rule "additive-only; consumers are tolerant readers" is in the contract doc but not enforced by any test or schema check.
5. JSON Schemas in `specs/events` were not validated in CI, and the review checklist item was not applied because the change looked like an internal refactor.

Contributing factors: contract drift, missing test, deploy order (consumer before producer was violated because both changed).

What went well: DLT preserved the messages with headers; idempotency made replay safe; alert fired within 19 minutes. Lucky: release had no migration, so rollback was trivial. Poorly: 19 minutes to detect; DLT depth was not alerted.

| ID | Action | Type | Owner | Priority | Due | Status |
|---|---|---|---|---|---|---|
| A-1 | Add consumer contract test that feeds N-1 producer payloads to the N consumer | prevent | backend lead | P0 | +2 weeks | open |
| A-2 | Validate event payloads against `specs/events/*.schema.json` in CI | prevent | platform lead | P1 | +4 weeks | open |
| A-3 | Alert when `oms.events.dead_lettered` increases for 2 minutes | detect | platform lead | P0 | +1 week | open |
| A-4 | Add "optional-first" rule and rollout order (consumer first) to the release checklist | process | engineering manager | P1 | +1 week | open |
| A-5 | Write and rehearse the DLT replay procedure | mitigate | backend lead | P1 | +3 weeks | open |
