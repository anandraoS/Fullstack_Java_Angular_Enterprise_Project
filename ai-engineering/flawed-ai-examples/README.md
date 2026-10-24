# Flawed AI-generated code: a regression library

AI assistants write code that compiles, passes the happy-path test and reads well, and still fails in exactly the
places a distributed order platform is fragile. Each example here has the same four parts:

1. the flawed code an assistant plausibly produces for a plain-English prompt,
2. why it is risky in THIS system,
3. the corrected code,
4. a regression test that proves the flaw manifests on the flawed version and is absent on the corrected one.

The examples are illustrative reproductions written for this repo. They were not harvested from a real incident or from
a specific model run, and no AI agent executed them as part of producing the repo (see `../agents/README.md`).

## Runnable Java examples (10, standalone Maven project)

`java/` is NOT part of the `services/` reactor. Java 17, JUnit 5 only, no Spring, no Docker, no network at test time.
Concurrency tests are deterministic: a `CyclicBarrier` forces the bad interleaving, so they do not rely on luck.

```
JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12 \
  ~/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn \
  -B -f ai-engineering/flawed-ai-examples/java/pom.xml test
```

Last recorded result when this library was written: `Tests run: 37, Failures: 0, Errors: 0, Skipped: 0` (BUILD
SUCCESS). A "passing" test on a flawed class means "the bug reproduced", which is the point.

| # | Flaw | Write-up | Code | Test class (tests) | Where the real system defends |
|---|---|---|---|---|---|
| 01 | Check-then-act overselling | [01](01-check-then-act-oversell.md) | `Ex01Oversell` | `Ex01OversellTest` (3) | inventory-service atomic conditional `UPDATE`, ADR-0006 |
| 02 | `@Transactional` self-invocation | [02](02-transactional-self-invocation.md) | `Ex02SelfInvocation` | `Ex02SelfInvocationTest` (3) | separate collaborator beans, ADR-0009 |
| 03 | Kafka publish inside the tx, no outbox | [03](03-publish-in-tx-no-outbox.md) | `Ex03DualWrite` | `Ex03DualWriteTest` (5) | `platform-common` `outbox` package, ADR-0009 |
| 04 | Non-idempotent consumer | [04](04-non-idempotent-consumer.md) | `Ex04NonIdempotentConsumer` | `Ex04NonIdempotentConsumerTest` (4) | `IdempotentEventProcessor`, ADR-0005 |
| 05 | Trusting `customerId` from the body (BOLA) | [05](05-bola-customer-id.md) | `Ex05Bola` | `Ex05BolaTest` (4) | `CurrentUser.canAccessCustomer`, threat-model |
| 06 | Logging PII and JWTs | [06](06-pii-and-jwt-logging.md) | `Ex06PiiLogging` | `Ex06PiiLoggingTest` (3) | `LogMasking`, contract section 10 |
| 07 | Unbounded pagination and N+1 | [07](07-unbounded-pagination-n-plus-one.md) | `Ex07PaginationNPlusOne` | `Ex07PaginationNPlusOneTest` (4) | `PageRequestFactory`, contract section 12 |
| 08 | String-built SQL | [08](08-string-built-sql.md) | `Ex08StringBuiltSql` | `Ex08StringBuiltSqlTest` (4) | bound parameters, sort whitelist |
| 09 | Catch `Exception` and ack | [09](09-catch-exception-and-ack.md) | `Ex09CatchAndAck` | `Ex09CatchAndAckTest` (5) | `DefaultErrorHandler` + DLT, `NonRetryableEventException` |
| 10 | Mutable state in a singleton | [10](10-mutable-singleton-state.md) | `Ex10SingletonState` | `Ex10SingletonStateTest` (2) | stateless services, code-review checklist |

Source root: `java/src/main/java/com/enterprise/oms/aiexamples/`, tests in `java/src/test/java/...`.

## Angular examples (2, documentation only)

| # | Flaw | Write-up |
|---|---|---|
| 11 | Subscription leaks (manual `subscribe` in components, polling that never stops) | [11](11-angular-subscribe-leaks.md) |
| 12 | Route guard treated as security | [12](12-angular-guard-is-not-security.md) |

The Jasmine specs in those write-ups are illustrative snippets. They were NOT executed as part of this library; the
runnable Angular tests live in `frontend/oms-ui` (see `docs/governance/test-strategy.md`).

## Honest limits of the models

* `Ex02` models the Spring proxy rule with a JDK dynamic proxy; it proves the mechanism, not Spring itself. The
  Spring-level regression (a `@SpringBootTest` that asserts a rollback) belongs in the owning service module.
* `Ex03` models "DB" and "broker" in memory. Real broker/DB crash windows are covered, with Docker, by
  `OutboxRelayKafkaTest` in `services/platform-common` (skipped on a machine without Docker).
* `Ex08` inspects the SQL text; it does not need a database to show the flaw. Real services bind parameters via
  Spring Data/JPA.

## How to use this library in reviews

Paste the relevant write-up path into the PR conversation (`@ai-engineering/flawed-ai-examples/01-...md`) when an AI
assistant produces similar code, and ask it to apply the corrected pattern plus the regression test.
