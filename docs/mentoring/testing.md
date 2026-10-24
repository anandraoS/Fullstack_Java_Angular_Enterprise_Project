# Mentoring: Testing

Verified on disk: `services/platform-test-support` (`AbstractKafkaIT`, `AbstractPostgresIT`, `Awaits`, `JsonAssertions`, `JwtTestTokens`), and in platform-common the tests `ExceptionHandlerApiTest`, `EventEnvelopeTest`, `IdempotentProcessorAndAuditTest`, `ConsumerKafkaTest`, `OutboxRelayKafkaTest`, `LogMaskingTest`. Service-level tests now exist in every service (for example `OrderApiTest`, `IdempotencyApiTest`, `ReservationConcurrencyTest`, `InventoryApiTest`, `OrderSagaKafkaIT`); the snippets below are still teaching sketches.

Honesty rule for this repo: the dev machine has no Docker, so tests annotated `@Testcontainers(disabledWithoutDocker = true)` are **skipped** there. A skipped test is not a passing test; never report it as passed.

---

## 1. "It passes on my machine" with Thread.sleep

**PR:** test for the outbox relay.

```java
// sketch - BEFORE
outbox.append(TOPIC, "k1", envelope);
Thread.sleep(3000);
assertEquals(1, consumedRecords.size());
```

**Reviewer:** Three seconds is both too long on a fast laptop and too short on a loaded CI runner. You are testing the scheduler's mood.

**Junior:** What else can I do, it is asynchronous?

**Reviewer:** Poll for the condition with a deadline. `platform-test-support` has `Awaits`; use it (or Awaitility) and assert on the outcome:

```java
// sketch - AFTER
outbox.append(TOPIC, "k1", envelope);
Awaits.until(Duration.ofSeconds(10), () -> consumedRecords.size() == 1);
assertThat(consumedRecords.get(0).headers().lastHeader("eventId")).isNotNull();
```

Passing means fast, failing means a clear timeout message. `OutboxRelayKafkaTest` (verified) is the model: embedded KRaft Kafka, no Docker needed.

**Takeaway:** no fixed sleeps; poll with a deadline; assert on headers too, because consumers depend on `correlationId`, `eventType`, `schemaVersion`, `eventId`.

---

## 2. The oversell test must actually race

**PR:** "Test reservation atomicity".

```java
// sketch - BEFORE
@Test void reservesStock() {
    reservations.reserve(order1, line(SKU_1019, 2));
    reservations.reserve(order2, line(SKU_1019, 2));
    assertTrue(stock(SKU_1019).reserved() <= 3);
}
```

**Reviewer:** Sequential calls cannot fail on a race. Also `<= 3` passes if nothing was reserved. SKU-1019 has stock 3 in the seed data (contract section 9). Fire many contenders at once and assert exact numbers.

```java
// sketch - AFTER (inventory-service; the real test is `ReservationConcurrencyTest`)
@Test
void neverOversells_underContention() throws Exception {
    int contenders = 20;                         // each wants 1, stock is 3
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(contenders);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
        UUID orderId = UUID.randomUUID();
        results.add(pool.submit(() -> {
            start.await();
            return reservations.reserve(orderId, List.of(new Line(SKU_1019_ID, 1))).isReserved();
        }));
    }
    start.countDown();
    long ok = 0; for (var f : results) if (f.get(10, TimeUnit.SECONDS)) ok++;
    pool.shutdown();

    assertThat(ok).isEqualTo(3);
    var s = stock(SKU_1019_ID);
    assertThat(s.reserved()).isEqualTo(3);
    assertThat(s.onHand() - s.reserved()).isGreaterThanOrEqualTo(0);
}
```

**Junior:** H2 vs PostgreSQL: do I trust the H2 result?

**Reviewer:** H2 in `MODE=PostgreSQL` runs the same Flyway SQL and exercises the conditional UPDATE, which is good for logic. Its locking differs from PostgreSQL's row locks and MVCC; the PostgreSQL variant extends `AbstractPostgresIT` and is skipped without Docker. Say so in the PR description: "H2 run: pass; PG Testcontainers: not executed (no Docker)".

**Takeaway:** concurrency tests need a start latch, many threads, exact assertions, and honest labelling of which database ran.

---

## 3. Mock the boundary, not the unit

**PR:** a test for `CreateOrderUseCase` that mocks `OrderPricing` and `OrderStateMachine`.

**Reviewer:** Pricing and the state machine are pure; construct the real ones. Mock only things that cross a process boundary: the inventory client port and the clock.

```java
// sketch
@ExtendWith(MockitoExtension.class)
class CreateOrderUseCaseTest {
    @Mock ProductCatalogClient catalog;
    @Mock OutboxService outbox;                 // verified class: platform-common
    InMemoryOrderRepository orders = new InMemoryOrderRepository();
    CreateOrderUseCase useCase;

    @BeforeEach void setUp() {
        useCase = new CreateOrderUseCase(new InMemoryIdempotencyGate(), catalog,
                new OrderPricing(), orders, new OrderStateMachine(), outbox);
    }

    @Test void appendsOrderCreatedToOutbox_inSameUnitOfWork() {
        when(catalog.validate(any())).thenReturn(ValidationResult.ok(lines("SKU-1001", 2, "12.50")));
        Order o = useCase.execute(command("Idem-Key-0001", CUSTOMER_1));
        assertThat(o.totalAmount()).isEqualByComparingTo("25.00");
        verify(outbox).append(eq("oms.order.events.v1"), eq(o.id().toString()), argThat(e -> e.eventType().equals("OrderCreated")));
    }
}
```

**Takeaway:** mocks for I/O, real objects for logic; verify the externally visible effect (outbox append), not internal method calls.

---

## 4. Test the HTTP contract, including the failures

**Verified model:** `ExceptionHandlerApiTest` in platform-common asserts problem+json shape.

**Reviewer:** For every endpoint, tests for: 200/202 happy path, 400 (`VALIDATION_FAILED` with `errors[]`), 401, 403, 404 for another customer's resource (not 403), and for `POST /orders` the idempotency matrix.

```java
// sketch - MockMvc
@Test void replayReturnsOriginalWithHeader() throws Exception {
    String body = json(validOrder(CUSTOMER_1));
    var first = mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "Idem-Key-0001")
            .with(jwtFor(CUSTOMER_1)).contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn();
    mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "Idem-Key-0001")
            .with(jwtFor(CUSTOMER_1)).contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotent-Replay", "true"))
        .andExpect(content().json(first.getResponse().getContentAsString()));
}

@Test void sameKeyDifferentBody_is422() throws Exception {
    // ... first call as above, second with quantity changed
    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
}
```

`JwtTestTokens` (verified) generates tokens at runtime from an ephemeral secret, so no secret is committed.

**Takeaway:** assert `code`, `status`, and `correlationId` presence on errors, not just status.

---

## 5. Consumer tests: duplicates and poison messages

**Verified model:** `ConsumerKafkaTest`.

**Scenarios every consumer must cover**

| Scenario | Expected |
|---|---|
| Same `eventId` delivered twice | effect once; `oms.events.duplicates` +1 |
| Unknown `eventType` | ignored; `oms.events.ignored` +1; offset advances |
| Malformed JSON | no retries; record in `<topic>.DLT` with `x-exception-class` |
| Handler throws transient exception | 4 attempts (500ms x2 up to 5s), then DLT |
| Handler throws `IllegalArgumentException` | no retries, straight to DLT |

```java
// sketch
@Test void duplicateDelivery_appliesEffectOnce() {
    var env = reserveInventoryEnvelope(orderId);
    consumer.onMessage(record(env)); consumer.onMessage(record(env));
    assertThat(reservationsFor(orderId)).hasSize(1);
    assertThat(meter("oms.events.duplicates")).isEqualTo(1.0);
}
```

---

## 6. The pyramid for this repo

| Layer | Examples | Runs without Docker |
|---|---|---|
| Unit (pure) | `OrderStateMachine` pair table, pricing, `LogMasking` | yes |
| Slice / MockMvc | controllers, security, problem+json | yes (H2) |
| Embedded Kafka | outbox relay, consumers, DLT | yes (EmbeddedKafka KRaft) |
| Testcontainers PG/Kafka | row-lock behaviour, real broker | **skipped** (`disabledWithoutDocker`) |
| E2E / smoke | `scripts/smoke-test.mjs`, `tests/e2e` | needs `scripts/run-local.sh` (JVMs, no Docker) |

## Checklist

- [ ] No `Thread.sleep`; poll with deadlines.
- [ ] Concurrency tests use latches and exact assertions.
- [ ] Every endpoint has failure-path tests asserting the problem `code`.
- [ ] Every consumer has duplicate, unknown-type, malformed and exhausted-retry tests.
- [ ] PR description states which tests were skipped and why.
