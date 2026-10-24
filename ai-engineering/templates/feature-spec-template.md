# Feature spec: <name>

Status: Draft | Approved | Implemented | Verified     Owner: <name>     Date: <yyyy-mm-dd>
Related: ADRs <...>, contract sections <...>, runbooks <...>

## 1. Problem and goal
One paragraph: who is hurt today, what outcome we want, how we will know (metric).

## 2. Scope
In scope: ...        Out of scope (explicit): ...

## 3. Actors and permissions
| Actor | May do | May not |
|---|---|---|

## 4. Functional requirements
FR-1 ... (numbered, testable, one sentence each)

## 5. Behaviour: acceptance criteria (Given/When/Then)
AC-1 Given ... When ... Then ...   (include: happy path, validation, authz, idempotency, concurrency, failure)

## 6. Design
* API changes (method, path, request, response, error `code`s, status codes)
* Domain / state changes (table of transitions)
* Data changes (migrations V<n>, constraints, indexes)
* Events (topic, type, schemaVersion, payload, key; producer/consumers)
* Sequence (numbered steps; mark which are transactional boundaries)
* Failure and recovery behaviour; compensation

## 7. Non-functional requirements
Latency, throughput, availability, data retention, observability (metrics/logs/alerts), accessibility.

## 8. Security considerations
Threats touched (link `docs/security/threat-model.md` ids), authz rules, data sensitivity, logging constraints.

## 9. Test plan and traceability
| AC | Test class#method | Level | Status (exists/pending) | Needs Docker |
|---|---|---|---|---|

## 10. Rollout
Feature flag?, migration order, backward compatibility, rollback, docs to update, release note.

## 11. Open questions / decisions needed
## 12. Verification record (filled at the end)
Commands run, counts, what was skipped or not verified.
