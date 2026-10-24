# Java standards (Java 17, Spring Boot 3.5)

Load when: writing or reviewing Java.

* Language: records for DTOs and events payloads; sealed/enums for closed sets; `Optional` only as return type;
  no `null` collections (return empty). `BigDecimal` for money (scale 2, `RoundingMode.HALF_UP`), never `double`.
* Time: `Instant` in domain/DB, ISO-8601 UTC at the edge; inject `Clock` so tests control time.
* Transactions: `@Transactional` on service methods (public, on a bean called from outside; no self-invocation, see
  flawed-ai-examples/02). Keep transactions short: no REST/Kafka calls inside. Read paths `readOnly = true`.
* Exceptions: throw the typed exceptions in `platform-common` `error` package (`NotFoundException`, `ConflictException`,
  `InvalidStateTransitionException`, `InsufficientStockException`, ...); `GlobalExceptionHandler` maps them. Never catch
  `Exception` to swallow; never return exception messages that include SQL or internals.
* Concurrency: services are stateless singletons; no mutable fields. Use DB constraints and atomic statements for
  correctness, not `synchronized`.
* JPA: no `EAGER` on collections; fetch child sets with `IN`/batch; DTO projections for lists; `@Version` only where
  optimistic locking is the chosen strategy (admin stock edit).
* Logging: SLF4J with placeholders, ids not payloads; mask with `LogMasking`; no `System.out`, no
  `printStackTrace`. Levels: `info` business milestones, `warn` recoverable anomalies, `error` needs a human.
* Validation: Bean Validation on request DTOs (`@Valid`), plus domain invariants in the domain class. Paging via
  `PageRequestFactory`; request ids via `IdempotencyKeys`.
* Style: constructor injection, `final` fields, package-private where possible, methods under ~30 lines, no wildcard
  imports. No Lombok unless the module already uses it (check before adding).
* Dependencies: none added without noting why; versions managed by the parent BOM; no snapshot versions.
* Tests: JUnit 5 + AssertJ/Mockito; see `07-test-generation.md`.
