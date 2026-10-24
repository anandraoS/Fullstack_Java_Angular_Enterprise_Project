# Code Review Scenarios

Seven realistic pull requests, each containing a defect that is plausible in this platform's design. Every scenario has: the PR diff, detailed reasoning, severity, the fix, and a regression test.

Important: the diffs are **reconstructed teaching examples** written against the contract (`docs/architecture/00-shared-contract.md`). order-service and inventory-service now exist on disk, but the diffs were written before them, so class names such as `ReservationService` or `OrderController` are placeholders for whatever the final code is called. Classes under `services/platform-common` are real. Test snippets are not yet compiled against the services; treat them as ready-to-adapt templates.

| # | Scenario | Severity | Category |
|---|---|---|---|
| [01](01-oversell-race.md) | Check-then-act stock reservation oversells | Critical | Concurrency / data integrity |
| [02](02-bola-customerId-from-body.md) | `customerId` trusted from request body | Critical | Authorization (BOLA) |
| [03](03-transactional-self-invocation.md) | `@Transactional` bypassed by self-invocation | High | Spring proxies |
| [04](04-kafka-publish-in-tx.md) | `KafkaTemplate.send` inside the DB transaction | High | Messaging consistency |
| [05](05-offset-ack-on-exception.md) | Exception swallowed, offset acknowledged | High | Messaging reliability |
| [06](06-unbounded-pagination-n-plus-one.md) | Unbounded list + N+1 queries | Medium (High at scale) | Performance / DoS |
| [07](07-pii-in-logs.md) | Email, token and body logged | High | Privacy / security |

## Severity scale used

| Level | Definition | Merge policy |
|---|---|---|
| Critical | Data corruption, money/stock loss, or cross-tenant data access reachable by an ordinary user | Block; fix before merge; add regression test |
| High | Silent loss/duplication of events, credential or PII exposure, outage risk under normal load | Block |
| Medium | Degrades under growth, maintainability traps | Fix or file a ticket with owner and date |
| Low | Style, naming | Optional |

## Review method (applies to every PR)

1. Read the tests before the code: do they prove the claimed behaviour, or only the happy path?
2. For every write: which transaction, what happens on retry, what happens if two run at once?
3. For every endpoint: who may call, which object, what do they get if not allowed?
4. For every message: is it published after commit, consumed idempotently, and acknowledged only after success?
5. For every query: how many rows, how many statements, which index?
6. For every log line: could it contain a credential or personal data?

## Practising on AI-written code

Flawed AI-generated examples live in [../../ai-engineering/flawed-ai-examples/](../../ai-engineering/flawed-ai-examples/) (on disk at time of writing: check-then-act oversell, transactional self-invocation, publish-in-tx, non-idempotent consumer, BOLA, PII/JWT logging, unbounded pagination, and more). They overlap with scenarios 01-07 here; apply the same method there.

Note: since this folder was drafted, `services/inventory-service` and `services/order-service` appeared on disk (for example `ReservationStrategy` implementations and `OrderStateMachine`). The diffs here remain reconstructed teaching examples and are not copies of that code.

## Related

* Mentoring dialogues: [../mentoring/README.md](../mentoring/README.md)
* Operational counterparts of these defects: [../runbooks/README.md](../runbooks/README.md)
