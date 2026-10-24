# Test generation rules

Load when: asking an assistant to write or extend tests. Strategy: `docs/governance/test-strategy.md`.

## Pyramid for this repo
1. Unit (fast, no Spring): pure domain (`OrderStateMachineTest`, `FulfillmentStateMachineTest`), helpers (`LogMaskingTest`,
   `PageRequestFactoryTest`, `EventEnvelopeTest`).
2. Slice/API (MockMvc or `@SpringBootTest` with H2 PostgreSQL mode, Flyway real SQL): controllers, security matrix,
   error shapes (`ExceptionHandlerApiTest`, `SecurityApiTest`, `FulfillmentApiTest`).
3. Integration with Kafka (embedded KRaft; or Testcontainers when Docker exists, otherwise skipped): outbox relay,
   consumers, DLT (`OutboxRelayKafkaTest`, `ConsumerKafkaTest`).
4. E2E (`tests/e2e` Playwright against a mock backend; full-stack smoke via `scripts/smoke-test.sh`).

## Rules for generated tests
* Name by behaviour: `cancelAfterReservationNeedsCompensation`, not `testCancel1`. One behaviour per test.
* Arrange-Act-Assert; assert outcomes (state, rows, events, status codes, problem `code`), not implementation calls.
* Every bug fix starts with a test that fails for the right reason. Show the failure before the fix when possible.
* Concurrency tests must be deterministic: use `CountDownLatch`/`CyclicBarrier` to force the interleaving; never `sleep`
  to "wait for it". Use `Awaits` from `platform-test-support` for eventually-consistent assertions with a timeout.
* Time: inject `Clock`; no `Thread.sleep` for TTL logic.
* Data: synthetic only; seed ids from contract section 9; generate secrets/tokens at runtime with `JwtTestTokens`.
* Security tests are mandatory for new endpoints: no token (401), wrong role (403), other customer's object (404), valid (2xx).
* Idempotency tests are mandatory for new consumers: same event twice, concurrent duplicate, effect failure then retry.
* State machine tests enumerate the full matrix of (state, trigger) pairs: valid ones and every invalid one.
* Do not mock what you own at the boundary under test (use H2 + real Flyway for repositories); mock only remote services.
* Docker-dependent tests: `@Testcontainers(disabledWithoutDocker = true)`; the report must list them as skipped.

## Prompt skeleton
"Given spec `ai-engineering/specs/features/<x>.md`, generate tests for acceptance criteria AC-n..AC-m. Use existing
helpers in `services/platform-test-support`. List which criteria are covered by which test method and which need Docker."
