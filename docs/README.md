# Documentation index

All data is synthetic. Start with the root `README.md`, then read in this order.

| Folder / file | Content |
|---|---|
| `pdf/` | The two generated PDFs (architecture; interview deep dive) and the section sources used to build them |
| `architecture/` | `00-shared-contract.md` (normative), saga and consistency, delivery semantics, event schema evolution, API versioning, Angular architecture, React architecture |
| `adr/` | Twelve architecture decision records |
| `governance/` | HLD, LLD, test strategy, risk register, sprint plan, deployment and rollback, definition of ready/done, checklists, PIR template |
| `runbooks/` | Eight production troubleshooting runbooks and their index |
| `security/` | Threat model and secure coding guide |
| `database/` | EXPLAIN ANALYZE study, lock contention and pool exhaustion study |
| `api/examples.md` | 38 real API request/response examples captured from the live stack |
| `validation/` | `validation-report.md` (what ran), `integration-defects.md` (11 defects found and fixed), `backend-test-summary.md`, `correlation-trace-sample.md` |
| `known-limitations.md` | Honest limitations and not-executed list |
| `traceability/` | Job-description traceability matrix with evidence and limitations per requirement |
| `interview/` | 17 reference chapters plus the React/Redux chapter 12b, and 164 questions (Q1 to Q164; Q145 to Q150 come from real defects, Q151 to Q164 cover the React front end) |
| `tdd/`, `code-review/`, `mentoring/` | Red-green-refactor write-ups, seven flawed-PR exercises, mentoring guides |
| `diagrams/` | Mermaid sources and rendered PNGs used by the PDFs |
| `_todo-link-check.md` | Record of the documentation reference re-check |

Rebuild the PDFs from `tools/pdf` (`node check-refs.mjs`, `node build-pdf1.mjs`, `node build-pdf2.mjs`, `node verify-pdfs.mjs`). Build the deliverable archive with `python -I scripts/make-zip.py`.
