# Token optimization guide

Goal: give an assistant exactly the context it needs, and nothing it will have to re-read. Token figures below are rough
estimates (about 4 characters per token); measure with your tool's usage view before relying on them.

## 1. Context layering

| Layer | Content | Load policy | Approx size |
|---|---|---|---|
| L0 always | `context/00-repo-context.md` (invariants, commands, map) | every session | ~1.5k tokens |
| L1 task rules | one or two of `context/01..10` | by task type (routing table below) | 0.5-1k each |
| L2 spec | one `specs/features/<x>.md` | when implementing/testing that feature | 2-4k |
| L3 contract slice | the single contract section needed (e.g. section 7 only) | by line range, not whole file | 1-3k |
| L4 code | the files being changed + their direct tests | by path, using grep first | variable |
| L5 evidence | failing test output, `git diff` | only the tail/failing part | small |

The contract is about 8k tokens in full. Reading it whole once per session is fine; re-reading it every task is waste.
Ask for a section ("contract section 7") or give a line range.

### Routing table
| Task | Load |
|---|---|
| New endpoint | 00, 04, 05, 07 |
| Event producer/consumer | 00, 09, 07, 10 if order state is involved |
| Migration | 00, 06 |
| Saga/state change | 00, 10, 09, 07 |
| Angular screen | 00, 03, 04 (API shapes) |
| React screen, slice or RTK Query endpoint | 00, 03b (React standards), 04 (API shapes); load `docs/architecture/react-architecture.md` by section only. Do not load 03 unless you are porting behaviour from the Angular UI |
| Contract change that touches both UIs | 00, 03, 03b, the contract slice; then edit both `models.ts` copies |
| Bug in production | 00 + the runbook + the failing test, not the whole architecture |
| Review a PR | 00, 05, the PR checklist, the diff |

## 2. @-includes
Reference files by path instead of pasting them: `@ai-engineering/context/09-kafka-events.md`, `@docs/adr/0006-...md`.
In Claude Code, put the L0 file in `CLAUDE.md` (or have `CLAUDE.md` import it with `@ai-engineering/context/00-repo-context.md`)
so it is loaded automatically; keep that file short because it is paid for on every turn. Do not `@`-include directories
or `node_modules`, generated files (`target/`, `dist/`), lockfiles, or the 20-product seed SQL.

## 3. Summarization
* Long threads: ask for a handoff summary (decisions, files changed, commands run with results, open questions) and
  start a fresh session with L0 + that summary + the spec.
* Large outputs (test logs, `kubectl describe`, heap histograms): pipe through `tail`, `grep -E "FAIL|ERROR|Tests run"`,
  or `head`; paste only the failing excerpt.
* Keep a running "verification record" at the bottom of the spec (commands, counts, skipped); summaries should cite it.
* Compress prior decisions into ADR one-liners (the ADR index `docs/adr/README.md` is the summary layer).

## 4. When to use subagents
Use a subagent when work is independent, read-heavy, or would flood the main context:
* Broad searches ("where is every place that publishes to Kafka?") - the subagent returns the answer, not the files.
* Independent parallel streams with disjoint files (docs vs tests vs code) - see `agents/`.
* A fresh-eyes review (security, tests) that should not inherit the author's assumptions.
Do NOT use a subagent for: a single known-file edit, a quick grep, anything needing the main thread's accumulated
reasoning, or tightly coupled changes across the same files (merge conflicts and duplicated reading cost more than
they save). A subagent costs its own context; its benefit is that only the report returns.

Rules: give it the spec path and the exact output format; state files it may NOT touch; require it to say what it did not
verify; treat its report as a claim to spot-check, not as proof.

## 5. Cheap habits that matter
1. Grep before read; read ranges, not whole files.
2. One task per session; a new task starts clean.
3. Tell the assistant the build command and the single test class to run (`-Dtest=...`), not "run all tests".
4. Prefer a failing test as the prompt: it states the requirement and the done condition in a few lines.
5. Ask for diffs/patch summaries, not re-printed files.
6. Keep prompts in `prompts/prompt-library.md` so wording is reused, not reinvented.
7. Avoid re-asking for context the assistant already has; instead say "per the spec section 6".

## 6. Anti-patterns
Pasting the whole contract and all ADRs into every prompt; letting `CLAUDE.md` grow past a page; attaching screenshots of
logs instead of the three relevant lines; asking an agent to "review everything".
