# Testing, TDD and BDD: From Unit Tests to Load Tests

This chapter explains how this platform proves that it works, and just as importantly how honestly it reports what it could not prove. The repository has roughly 13,000 lines of Java test code under `services/*/src/test` and `gateway/src/test`, 18 Angular spec files, a 15-test Playwright suite, four Gherkin feature files, two TDD narratives and two k6 scripts. Interviewers rarely want a tour of tools; they want to hear *why a test is at a given level*, *what it can and cannot prove*, and *how you stop tests from lying*. Every example below is a real file you can open.

## Big picture and kid-friendly analogy

Imagine you are building a bicycle.

* First you test every part alone on the workbench: does this bolt turn, does this brake lever spring back? That is a **unit test**. It is tiny and instant, so you can do thousands.
* Then you check that parts work together: when I squeeze the lever, does the brake pad actually touch the wheel? That is an **integration test**. It is slower because several parts are involved.
* Then you ride the whole bike down an empty parking lot, and finally down a real street with cars. That is an **end-to-end test**. It is the most convincing, and the slowest, and when it fails you have to hunt for which part is guilty.
* Sometimes you cannot test the real thing, so you use a stand-in: a wooden dummy wheel (a **mock**) lets you test the brake without a real wheel, and a spare practice road (an **embedded broker**, an in-memory database) stands in for the real street. Stand-ins are cheap but they can lie: a dummy wheel never goes flat.
* **TDD** is writing the "does the brake work?" checklist *before* you attach the brake, watching it fail, attaching the brake, and watching it pass. **BDD** is writing that checklist in plain sentences a shop customer could read ("Given a rider at 20 km/h, when they squeeze the lever, then the bike stops in 5 metres").
* A **load test** is asking 1,000 friends to ride at once, to see when the parking lot jams.

```
                  /\            E2E: Playwright (15 tests, mock backend), smoke script
                 /  \           few, slow, broad
                /----\          API/contract: MockMvc, WebTestClient, OpenAPI export
               /      \         Integration: EmbeddedKafka, H2 + Flyway, Testcontainers PostgreSQL
              /--------\        Unit: state machines, helpers, Mockito, Angular specs
```

| Analogy | Technical concept |
|---|---|
| Part on the workbench | Unit test (JUnit 5 + AssertJ) |
| Dummy wheel | Mock or stub (Mockito) |
| Brake touching wheel | Integration test (Spring context, Flyway, Kafka) |
| Parking lot / street | Embedded Kafka and H2 / real PostgreSQL |
| Checklist before building | TDD (red, green, refactor) |
| Checklist in plain sentences | BDD scenarios (Gherkin) |
| 1,000 friends riding | Load test (k6) |
| Dummy wheel never goes flat | A mock cannot reveal real-world behaviour |

## Beginner tier

### Example 1: a pure unit test that checks a whole table of rules

`OrderStateMachine` (`services/order-service/src/main/java/com/enterprise/oms/order/domain/OrderStateMachine.java`) is a pure function: no Spring, no database, no clock. That makes it the ideal first test. `OrderStateMachineTest` (`services/order-service/src/test/java/com/enterprise/oms/order/domain/OrderStateMachineTest.java`) names each behaviour in plain language and then asserts the **entire** matrix of 9 statuses by 12 triggers, with the expectations written from the contract rather than derived from the code.

```java
@Test
void cancelBeforeReservationIsImmediate() {
    assertThat(OrderStateMachine.decide(CREATED, CANCEL_REQUESTED).to()).isEqualTo(CANCELLED);
    assertThat(OrderStateMachine.decide(VALIDATED, CANCEL_REQUESTED).to()).isEqualTo(CANCELLED);
}

@ParameterizedTest(name = "{0} + {1} = {2} {3}")
@MethodSource("everyStatusAndTrigger")
@DisplayName("every one of the 108 (status, trigger) pairs decides as the contract says")
void statusTriggerMatrix(OrderStatus from, OrderTrigger trigger, OrderStateMachine.Kind kind, OrderStatus to) {
    OrderStateMachine.Decision d = OrderStateMachine.decide(from, trigger);

    assertThat(d.kind()).isEqualTo(kind);
    assertThat(d.to()).isEqualTo(to);
}
```

What to say about it. JUnit 5 gives `@Test`, `@ParameterizedTest` with `@MethodSource` and `@EnumSource`, `@Nested` and `@DisplayName`. AssertJ gives fluent, readable failures (`assertThat(...).isEqualTo(...)`, `containsExactlyInAnyOrder`). Test names describe behaviour (`fulfilledOrderCannotBeCancelled`), not methods (`testCancel1`). One behaviour per test. The matrix test is cheap (the TDD document records `Tests run: 207` in about 2 seconds) and catches the classic "one wrong cell in a table that looks right on review". A property-style test such as `everyAllowedPairIsReachableThroughSomeTrigger` cross-checks two views of the same table against each other.

### Example 2: Mockito for collaborators you do not want to run

`ReservationServiceUnitTest` (`services/inventory-service/src/test/java/com/enterprise/oms/inventory/service/ReservationServiceUnitTest.java`) tests the orchestration of `ReservationService` with every collaborator mocked: no Spring, no database. The setup and one test show `mock()`, `when(...).thenReturn(...)`, `verify` and `ArgumentCaptor`.

```java
@BeforeEach
void setUp() {
    reservations = mock(ReservationRepository.class);
    claims = mock(ReservationClaims.class);
    strategy = mock(ReservationStrategy.class);
    events = mock(InventoryEventPublisher.class);
    when(strategy.name()).thenReturn("atomic");
    ...
}

@Test
void mergesAndSortsLinesByProductIdBeforeCallingTheStrategy() {
    when(reservations.findByOrderId(any())).thenReturn(Optional.empty());
    when(claims.claimReserved(any(), any(), any(), any())).thenReturn(true);
    when(strategy.tryReserve(any())).thenReturn(ReservationStrategy.Result.ok());

    service.reserve(UUID.randomUUID(), List.of(new QuantityItem(p2, 1), new QuantityItem(p1, 2),
            new QuantityItem(p2, 4)), null, "corr", null);

    ArgumentCaptor<List<QuantityItem>> lines = ArgumentCaptor.forClass(List.class);
    verify(strategy).tryReserve(lines.capture());
    assertThat(lines.getValue()).containsExactly(new QuantityItem(p1, 2), new QuantityItem(p2, 5));
}
```

The vocabulary an interviewer expects: a **stub** returns canned answers (`when/thenReturn`); a **mock** also records interactions you verify (`verify(...)`, `never()`); a **spy** wraps a real object so you can stub part of it; a **captor** grabs the argument that was passed so you can assert on its content. Here the interesting behaviour (lines are merged and sorted by product id, which is what prevents deadlocks) is observable only in the argument handed to the strategy, so a captor is the right tool. The same class uses a fixed `Clock` (`Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC)`) to assert the expiry time exactly, which removes `Thread.sleep` from time logic. The project's rule (`ai-engineering/context/07-test-generation.md`): "Do not mock what you own at the boundary under test; mock only remote services."

## Intermediate tier

### Example 1: strict stubs, lenient opt-outs, ordering and chained answers

Mockito's JUnit 5 extension runs in **strict stubs** mode by default: a stub that the test never uses fails the test with `UnnecessaryStubbingException`, which stops tests rotting into fiction. `FulfillmentServiceTest` and `NotificationServiceTest` use `@ExtendWith(MockitoExtension.class)` with that default. Two order-service tests deliberately opt out because their shared `@BeforeEach` wiring stubs more than any single test needs:

```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCommandServiceTest {

    @Mock CustomerRepository customers;
    @Mock OrderRepository orders;
    @Mock SagaInstanceRepository sagas;
    @Mock IdempotencyKeyRepository idempotencyKeys;
```

Be honest in an interview that lenient is a trade-off: convenient shared setup versus losing the unused-stub alarm. For ordering, `ReservationStrategyUnitTest` uses `InOrder` to prove the compensation loop gives back lines in reverse order and never touches the short line:

```java
InOrder order = inOrder(stock);
order.verify(stock).release(eq(b), eq(3), any());   // reverse order
order.verify(stock).release(eq(a), eq(2), any());
verify(stock, never()).release(eq(c), anyInt(), any());
```

And `OutboxRelayResilienceTest` chains answers to simulate a flaky broker: the first `send` returns a failed future, the second succeeds (`when(kafka.send(any(ProducerRecord.class))).thenReturn(failed).thenReturn(ok)`), and the test asserts that rows stay pending after cycle one and publish oldest-first after cycle two. There is no `@Spy` anywhere in the codebase; if you are asked, say a spy is useful for partially stubbing legacy classes but is a smell in new code, because needing it usually means the class does too much.

### Example 2: MockMvc and WebTestClient for the HTTP contract

Two styles exist because there are two stacks. The servlet services use `MockMvc`, which drives the real Spring MVC, security and exception-handling chain without opening a socket. The reactive gateway uses `WebTestClient` against a random port. First, the idempotency contract of `POST /orders` (`services/order-service/src/test/java/com/enterprise/oms/order/api/IdempotencyApiTest.java`):

```java
MvcResult first = mvc.perform(createOrder(token, key, body)).andExpect(status().isAccepted())
        .andExpect(header().doesNotExist("Idempotent-Replay")).andReturn();
MvcResult second = mvc.perform(createOrder(token, key, body)).andExpect(status().isAccepted())
        .andExpect(header().string("Idempotent-Replay", "true")).andReturn();

assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
assertThat(ordersOf(alice)).isEqualTo(1);
```

The same class includes a real concurrent race (`eightConcurrentSubmissionsStillCreateExactlyOneOrder`) and a cross-body conflict (`sameKeyWithADifferentBodyIs422AndCreatesNothing`). Second, the edge security matrix in the gateway (`gateway/src/test/java/com/enterprise/oms/gateway/EdgeSecurityTest.java`) asserts the error *shape*, not just the status code:

```java
req.exchange().expectStatus().isUnauthorized()
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .expectBody()
        .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
        .jsonPath("$.correlationId").isNotEmpty()
        .jsonPath("$.errors").isArray();
assertThat(TestEnv.ORDER.requests()).as("upstream must not be called").isEmpty();
```

The last line is the important one: the test proves the request never reached the upstream service. The mandatory set for any new endpoint (from `ai-engineering/context/07-test-generation.md`) is: no token gives 401, wrong role 403, another customer's object 404 (not 403, to avoid enumeration), valid request 2xx, plus 400 validation and 409 state where relevant. Forged-token cases (expired, wrong audience, wrong issuer, tampered, `alg:none`) live in `SecurityApiTest` (`algNoneTokenIs401`) and the gateway matrix.

### Example 3: Kafka tests without Docker, and waiting without sleeping

`AbstractKafkaIT` (`services/platform-test-support/src/main/java/com/enterprise/oms/testsupport/AbstractKafkaIT.java`) starts an embedded KRaft broker with all OMS topics and their dead-letter topics, and points `spring.kafka.bootstrap-servers` at it.

```java
@EmbeddedKafka(partitions = Topics.PARTITIONS, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.ORDER_EVENTS, Topics.ORDER_EVENTS + Topics.DLT_SUFFIX, ...})
public abstract class AbstractKafkaIT {
```

`ConsumerKafkaTest` (`services/platform-common/src/test/java/com/enterprise/oms/common/messaging/ConsumerKafkaTest.java`) uses the same annotation directly and proves the consumer contract end to end: the same event sent twice is processed once and counted as a duplicate, with the correlation id visible in the MDC; a poison message is retried with backoff and then dead-lettered; malformed JSON goes straight to the DLT without blocking the partition.

```java
send(env.aggregateId(), json);
send(env.aggregateId(), json); // redelivery of the same eventId

await().atMost(Duration.ofSeconds(30)).until(() -> metrics.duplicatesTotal() == dupBefore + 1);
assertThat(listener.handled).containsOnlyOnce(env.eventId());
assertThat(listener.mdcSeen.get(env.eventId())).isEqualTo("corr-consume-1");
```

Asynchronous assertions use Awaitility, wrapped by `Awaits` (`services/platform-test-support/src/main/java/com/enterprise/oms/testsupport/Awaits.java`: `until`, `untilAsserted`, and `neverWithin` for "nothing must arrive"). The reviewer rule in `docs/governance/code-review-checklist.md` is "use `Awaits` ... not `Thread.sleep`", because a fixed sleep is simultaneously too slow on a fast laptop and too short on a loaded runner. An embedded broker is a real Kafka broker, so protocol-level behaviour (partitions, DLT, headers, offsets) is genuinely exercised; what it does not exercise is TLS, multi-broker replication, or `min.insync.replicas`.

### Example 4: BDD as living documentation

The four files in `tests/bdd/*.feature` are Gherkin. Each declares its executable mirror in a header comment. For inventory:

```gherkin
  Scenario: The last unit goes to exactly one of two competing orders
    Given a product with 1 unit on hand
    When two orders reserve that unit at the same moment
    Then exactly one reservation is RESERVED and the other is FAILED
    And 1 unit is reserved and never more
```

There is no Cucumber runner. Instead each scenario is a JUnit `@Nested` class in `InventoryBddScenariosTest` whose `given()` and `when()` run before every `then_*` method, and whose `@DisplayName("Scenario: ...")` reuses the feature wording:

```java
@Nested
@DisplayName("Scenario: Reserve available stock")
class ReserveAvailableStock {
    void given() { product = newProduct(10); }
    void when() { outcome = reserve(order, item(product, 4)); }

    @BeforeEach void setUp() { given(); when(); }

    @Test void then_4_units_are_reserved_and_6_are_available() {
        assertThat(reserved(product)).isEqualTo(4);
```

The order service goes further with scenario ids (`@ORD-A1`, `@ORD-B1`) repeated in comments and a `Rule:` grouping. The honest trade-off: a Gherkin runner would keep the text and the code in one artifact but costs a framework and indirection; mirroring keeps the tests ordinary JUnit but the two files can drift, and nothing mechanically checks that they agree. Say that BDD's value is the conversation between product, QA and engineering that produces the scenarios, not the tooling.

### Example 5: Angular unit tests

The UI (`frontend/oms-ui`, Angular 20) uses Karma and Jasmine, run by `ng test --watch=false --browsers=ChromeHeadlessNoSandbox`. Because the server and gateway are authoritative, the UI tests prove UX and plumbing. `frontend/oms-ui/src/app/core/state/polling.spec.ts` tests time with `fakeAsync` and `tick`, so a 3-step exponential-backoff poll runs in microseconds:

```ts
pollUntil(() => of(values[n++]), (v) => v === 'FULFILLED', opts, new Subject()).subscribe({
  next: (v) => seen.push(v),
  complete: () => (completed = true),
});
expect(seen).toEqual(['CREATED']);
tick(100);
expect(seen).toEqual(['CREATED', 'VALIDATED']);
tick(199); // next delay is 200ms
expect(seen.length).toBe(2);
```

`frontend/oms-ui/src/app/core/http/interceptors.spec.ts` uses `HttpTestingController` to assert that a bearer token and `X-Correlation-Id` are added to API calls but not to the token endpoint or foreign origins; `frontend/oms-ui/src/app/core/auth/guards.spec.ts` is titled "route guards (UX only)" to keep the principle visible. The checked-in Istanbul report (`frontend/oms-ui/coverage/oms-ui/index.html`) shows statements 92.62%, branches 85.34%, functions 88% and lines 93.55%, but remember that coverage is a floor detector, not a quality measure.

## Advanced tier

### Example 1: concurrency tests that actually race, and a mutation check that proves they can fail

An oversell bug appears only under concurrency, so a sequential test proves nothing. `ReservationConcurrencyTest` (`services/inventory-service/src/test/java/com/enterprise/oms/inventory/ReservationConcurrencyTest.java`) releases all tasks at the same instant with two latches and gives every result a timeout so a deadlock shows up as a failure rather than a hang:

```java
CountDownLatch ready = new CountDownLatch(tasks.size());
CountDownLatch go = new CountDownLatch(1);
...
            ready.countDown();
            go.await();
            return t.call();
...
assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
go.countDown();
...
results.add(f.get(60, TimeUnit.SECONDS));   // a deadlock would surface here as a timeout
```

It is parameterized over all three reservation strategies (`atomic`, `pessimistic`, `optimistic`) and asserts exact numbers: 50 threads competing for 10 units must yield exactly 10 reserved and 40 failed, the ledger (`heldBy`) must equal `stock.reserved`, orders with opposite line orders must not deadlock, and 20 concurrent duplicates of one command must create exactly one reservation. Exact equality matters: `<= 10` would pass if nothing were reserved at all.

A green test is only meaningful if it can go red. `docs/tdd/inventory-reservation-tdd.md` records a **manual mutation check**: temporarily replacing the sorted product-id order with insertion order made `oppositeItemOrdersDoNotDeadlock` fail for all three strategies (`Transaction 17 has been chosen as a deadlock victim`), then the change was reverted. The same document admits that two other safety nets (the status guards on lifecycle updates and the `ck_stock_reserved_le_on_hand` CHECK constraint) were reasoned about but not mutation-tested. Automated mutation testing (PIT) is not configured; `docs/governance/test-strategy.md` lists "mutation / chaos" as "not planned for v1", and the prompt library (`ai-engineering/prompts/prompt-library.md`, prompt 11) asks an assistant to break the code in three plausible ways and report which tests fail. The pure-Java counterpart for teaching is `ai-engineering/flawed-ai-examples/java/src/test/java/com/enterprise/oms/aiexamples/Ex01OversellTest.java`, which uses a `CyclicBarrier` to force the bad interleaving deterministically.

### Example 2: Testcontainers, honest skipping and the H2 versus PostgreSQL gap

Local and CI databases are H2 in PostgreSQL mode with the same Flyway SQL, which is fast and portable but only approximates PostgreSQL's row locking and isolation. So the decisive concurrency scenarios are written once as an interface, `PostgresScenarios`, and run against a real PostgreSQL 16 by `InventoryPostgresIT` (Testcontainers). The base class is explicit that it skips instead of failing:

```java
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIT {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
```

On a machine without Docker these tests report as skipped. The generated failsafe reports on disk make it concrete: `InventoryPostgresIT` and `InventoryLocalPostgresIT` each show `Tests run: 9, ... Skipped: 9`, and the inventory-service summary reads `completed 32, skipped 18`; order-service reads `completed 27, skipped 7`. The `InventoryLocalPostgresIT` variant accepts any reachable PostgreSQL through environment variables, and the TDD document records a run against a real PostgreSQL 16.4 started from binaries already in the local Maven cache (16 tests, zero failures, all three strategies), but the later reports on disk show it skipped again, so its status depends on the environment each time. The rules that keep this honest: a skipped test is not a passed test; the PR must say which tests did not run (`docs/governance/code-review-checklist.md` gate question 0); the story is "Done - CI verification pending" until CI executes them (`definition-of-ready-and-done.md`). The risk register carries it as R-04 (portability gap) and R-03 (H2 does not prove PostgreSQL isolation).

Surefire versus failsafe: `services/pom.xml` runs `**/*Test.java` in surefire during `test` and `**/*IT.java` in failsafe during `integration-test` and `verify`. Slow, environment-dependent tests are named `*IT` so a quick `mvn test` stays quick.

### Example 3: a TDD walk-through with real red and green output

`docs/tdd/order-state-machine-tdd.md` is a recorded history rather than a reconstruction. The cycles, in short:

1. **Red, does not compile.** One test calls `OrderStateMachine.decide(CREATED, VALIDATION_PASSED)` before the class exists. Writing the test forced two design decisions: a `Decision` carries a `kind` and a target, and `decide` is a plain function.
2. **Green, minimal.** Hard-code exactly one rule. `Tests run: 1, Failures: 0`.
3. **Red, behaviour.** Add cancel-after-reservation, a fulfilled-order-cannot-be-cancelled test and a replay test. The replay test created the third outcome `NO_OP`, the main modelling decision: a Kafka consumer sees duplicates all day, so "already applied" is harmless, while `INVALID` is a real conflict (HTTP 409).
4. **Refactor to tables.** Once the 9x12 matrix was written as data, the `switch` could not carry 12 triggers, so logic became three tables and `decide` became a lookup. The suite stayed green throughout (`Tests run: 207`), which is the point of refactoring under tests.
5. **Mutation check.** Deleting `.move(VALIDATED, CANCELLED)` made five tests fail, including the matrix.

`docs/tdd/inventory-reservation-tdd.md` shows the other valuable TDD story: a first implementation using `INSERT ... ON CONFLICT DO NOTHING` was never going to be green because H2's PostgreSQL mode rejects it (32 of 33 executions errored). The fix, `ReservationClaims` (an insert inside a JDBC savepoint), was driven by a failing test, and the test run also exposed that `{"delta":1.5}` was silently truncated to `1`, fixed by `spring.jackson.deserialization.accept-float-as-int=false`. The interview line: TDD's payoff is not the first test, it is the safety net for the third rewrite and the design pressure on the API.

### Example 4: end-to-end, contract, coverage and load

**Playwright** (`tests/e2e/specs/order-lifecycle.spec.ts`, 15 tests in serial mode, one worker) drives the real UI with role-based locators (`getByRole`, `getByLabel`), covers login, the order lifecycle, cancellation with a focus-managed dialog, an idempotent duplicate submit with a simulated lost response, and operations screens. The configuration (`tests/e2e/playwright.config.ts`) has two modes: by default a Node mock backend (`tests/e2e/mock-backend/server.js`) serves the built UI and a fake API on port 4300; setting `E2E_BASE_URL` plus `E2E_DEMO_PASSWORD` targets a live stack. The risk register names the trap (R-17: "E2E suite gives false confidence because it runs against `tests/e2e/mock-backend`"): in mock mode these tests prove the UI against the contract, not the real services, and Playwright is not wired into `ci.yml`.

**Contract checks.** The only exported OpenAPI file is `specs/openapi/inventory-service.json`. `ActuatorAndDocsTest.openApiDocumentDescribesTheInventoryApiAndIsExportedForSpecs` asserts the documented paths exist; `scripts/export-openapi.mjs --check` is designed as a CI drift check against a running stack. Event schemas in `specs/events/*.schema.json` exist, but no test in the Java modules validates payloads against them (the post-incident template's sample action A-2 proposes exactly that), so event contract protection today is `EventEnvelopeTest` plus tolerant-reader tests, not schema validation. Mention consumer-driven contracts (Pact) as the standard answer for independent teams, and say it is not used here.

**JaCoCo** is configured for every module in `services/pom.xml` (`prepare-agent`, then `report` at `verify`) with the comment "No coverage gate (reports only)". The proposed gate in the test strategy is 80 percent line coverage and 90 percent on the state machine, saga and reservation classes.

**k6** (`performance/oversell.js`) is a correctness gate disguised as a load test: 40 contenders race for SKU-1019 (seed stock 3) and thresholds assert that no more than the expected stock is accepted.

```js
thresholds: {
  oversell_violations: ['count==0'],
  oversell_orders_accepted_stock: [`count<=${EXPECTED_STOCK}`],
  checks: ['rate==1'],
},
```

`performance/README.md` is blunt: "No results are included. None of these scripts has been executed." Its caveats are worth reciting: rate limiting returns 429, the simulated fulfillment delay dominates completion time, stock exhaustion turns later iterations into rejections, and H2 contention differs from PostgreSQL. A load test finds *that* a bottleneck exists; the dashboards in `observability/` find *where*.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Test strategy and pyramid | `docs/governance/test-strategy.md` | Counts partly stale; re-glob before quoting |
| Pure unit tests | `services/order-service/.../domain/OrderStateMachineTest.java`, `services/platform-common/.../observability/LogMaskingTest.java` | Matrix and property tests |
| Mockito | `ReservationServiceUnitTest`, `ReservationStrategyUnitTest`, `OrderCommandServiceTest`, `OutboxRelayResilienceTest` | Strict by default, two LENIENT classes, no `@Spy` |
| MockMvc API tests | `services/order-service/.../api/*ApiTest.java`, `InventoryApiTest.java` | Real security chain, H2 + Flyway |
| WebTestClient | `gateway/src/test/java/com/enterprise/oms/gateway/EdgeSecurityTest.java`, `RoutingAndHeadersTest.java` | Stub upstreams via `StubServer` |
| Embedded Kafka | `services/platform-test-support/.../AbstractKafkaIT.java`, `ConsumerKafkaTest.java`, `OrderSagaKafkaIT.java` | Runs without Docker |
| Testcontainers | `AbstractPostgresIT.java`, `InventoryPostgresIT.java`, `PostgresScenarios.java` | Skipped without Docker |
| Concurrency | `ReservationConcurrencyTest.java`, `IdempotencyApiTest.java` | Latch-forced races, exact asserts |
| BDD | `tests/bdd/*.feature`, `*BddScenariosTest.java` in four services | Manual mirror, no Cucumber |
| TDD narratives | `docs/tdd/*.md` | Real build output |
| Angular | `frontend/oms-ui/src/app/**/*.spec.ts`, `karma.conf.js` | 18 spec files |
| E2E | `tests/e2e/specs/order-lifecycle.spec.ts`, `tests/e2e/mock-backend/server.js` | Mock mode by default |
| Contract | `specs/openapi/inventory-service.json`, `scripts/export-openapi.mjs`, `ActuatorAndDocsTest.java` | One service exported |
| Coverage | `services/pom.xml` (JaCoCo), `frontend/oms-ui/coverage/` | No enforced threshold |
| Load | `performance/oversell.js`, `performance/order-flow.js` | Never executed |

### Demonstration-only vs production-grade

Production-grade: tests named by behaviour; exhaustive matrices for state logic; latch-based races with exact assertions; real Spring security and Flyway in API tests; embedded KRaft Kafka for messaging semantics; honest skipping with `disabledWithoutDocker`; test secrets generated at runtime (`JwtTestTokens`); deterministic waiting through `Awaits`. Demonstration-only or incomplete: Postgres-specific evidence depends on Docker and is skipped on the author machine; Playwright runs against a mock backend and is not in CI; k6 scripts have never run; no mutation tool, no coverage gate, no Pact, no schema validation of events in tests; the BDD mirror can drift from the feature files; the Angular coverage report is a snapshot, not a gate; and the chapter's own source `test-strategy.md` still lists some classes as pending that now exist, so treat it as a map, not a count.

## Common mistakes and gotchas

* **Testing implementation, not behaviour.** `verify(repo).save(any())` everywhere breaks on every refactor. Assert outcomes (state, rows, events, status codes, problem `code`) and verify only externally visible effects, such as an outbox append.
* **Over-mocking what you own.** Mocking `OrderStateMachine` in a use-case test proves nothing; construct the pure thing and mock only I/O and the clock.
* **`Thread.sleep` waits.** Poll with a deadline (`Awaits`); for "nothing happens" use `neverWithin`.
* **Concurrency tests that cannot fail.** Sequential calls, `<=` assertions and no start latch all pass on broken code. Force the interleaving, assert exact values, and prove the test can fail by mutating the code once.
* **Trusting H2 for isolation behaviour.** It runs the same SQL, not the same locks. Label every PR with which database ran.
* **Reporting skipped tests as passed.** Always state skipped counts and why.
* **Hard-coded secrets and shared mutable fixtures.** Generate tokens at runtime; give every test unique ids (`newProduct` uses random sku suffixes) so tests in a shared context never collide.
* **Flaky test tolerance.** A retry-until-green policy hides real races. Quarantine with a ticket or fix.
* **Mock-backed E2E counted as proof.** Say which backend ran.
* **Coverage worship.** 90 percent coverage with no assertions is decoration; coverage tells you what was never executed, not what was verified.
* **Assertion on the happy status code only.** Check the error body shape, headers, and that upstreams were not called.
* **Angular time without `fakeAsync`.** Real timers make specs slow and flaky.
* **Letting Mockito leniency spread.** Use `LENIENT` only with a stated reason.

## Quick-recall cheat sheet

* Pyramid: many fast unit tests, fewer integration tests, few E2E; test each behaviour at the cheapest level that can prove it.
* JUnit 5: `@Test`, `@ParameterizedTest` (`@MethodSource`, `@EnumSource`), `@Nested`, `@DisplayName`; AssertJ for assertions.
* Mockito: stub (`when/thenReturn`), mock (`verify`, `never`, `times`), spy (partial), captor (inspect arguments), `InOrder`; strict stubs flag unused stubs.
* Surefire runs `*Test` in `test`; failsafe runs `*IT` in `verify`.
* MockMvc for servlet stacks, WebTestClient for reactive; assert body shape and that upstreams were untouched.
* Embedded Kafka proves protocol behaviour without Docker; Testcontainers proves database behaviour but needs Docker and is skipped otherwise.
* Race tests: ready latch plus go latch, timeouts on results, exact assertions, three strategies in one suite.
* TDD: red (for the right reason), green (minimal), refactor (tests stay green); every bug fix starts with a failing test.
* BDD: Given/When/Then wording shared between product and tests; here mirrored in `@Nested` JUnit classes.
* Not present: PIT mutation testing, Pact, Cucumber runner, coverage gate, executed k6 results.
