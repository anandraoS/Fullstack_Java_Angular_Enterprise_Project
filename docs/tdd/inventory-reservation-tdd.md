# TDD narrative: inventory reservation core

How the reservation core of `services/inventory-service` was built test-first. Every excerpt below is real output
from the build log of that session (timings and ids trimmed); nothing is reconstructed from memory.

Contract rules under test (`docs/architecture/00-shared-contract.md`, section 7): atomic conditional UPDATE in sorted
productId order, all-or-nothing per reservation, one reservation per order (duplicate returns the original outcome),
no overselling under concurrency, same behaviour for three interchangeable strategies.

## Step 1 - write the tests first (RED)

Only the minimum needed to compile existed: the Maven module, the Flyway schema (`V10__inventory_schema.sql`), entities,
repositories, the `ReservationStrategy` interface with its three classes, and a **stub** service:

```java
@Service
public class ReservationService {
    @Transactional
    public ReservationOutcome reserve(UUID orderId, List<QuantityItem> items, Integer ttlSeconds, String correlationId,
                                      UUID causationId) {
        throw new UnsupportedOperationException("not implemented yet (TDD red)");
    }
}
```

`ReservationServiceTest` (18 test executions: 9 scenarios, 4 of them run once per strategy) and
`ReservationConcurrencyTest` (5 scenarios x 3 strategies) were written against that API. Behaviours specified up front:

| Behaviour | Test |
|---|---|
| reserve available stock, emit `InventoryReserved`, bump stock version | `reservesAvailableStockAndEmitsInventoryReserved` |
| refuse when insufficient, report shortage `(productId, requested, available)`, emit `InventoryReservationFailed` | `refusesWhenStockIsInsufficientAndReportsTheShortage` |
| all-or-nothing across lines | `isAllOrNothingAcrossLines` |
| duplicate order returns original outcome (also for a FAILED original) | `duplicateOrderReturnsTheOriginalOutcome...`, `duplicateOfAFailedReservation...` |
| unknown / inactive product refused without touching stock | `unknownProductIs...`, `inactiveProductIsRefused` |
| merge duplicate lines, reject invalid input, TTL default and cap | `duplicateLines...`, `invalidRequests...`, `ttlDefaults...` |
| 50 threads x 1 unit vs stock 10 = exactly 10 reserved | `fiftyThreadsCompeteForTenUnits...` |
| opposite item orders do not deadlock | `oppositeItemOrdersDoNotDeadlock` |
| 20 concurrent duplicate commands = 1 reservation | `concurrentDuplicateReserveCommandsCreateOneReservation` |

First run (`mvn -pl inventory-service test -Dtest=ReservationServiceTest`):

```
[ERROR] Tests run: 18, Failures: 1, Errors: 17, Skipped: 0
[ERROR] Failures:
[ERROR]   ReservationServiceTest.invalidRequestsAreRejectedAsIllegalArguments:165
Expecting actual throwable to be an instance of:
  java.lang.IllegalArgumentException
but was:
  java.lang.UnsupportedOperationException: not implemented yet (TDD red)
[ERROR] Errors:
[ERROR]   ReservationServiceTest.reservesAvailableStockAndEmitsInventoryReserved:44->reserve:33 UnsupportedOperation not implemented yet (TDD red)
[ERROR]   ReservationServiceTest.refusesWhenStockIsInsufficientAndReportsTheShortage:69->reserve:33 UnsupportedOperation not implemented yet (TDD red)
[ERROR]   ReservationServiceTest.isAllOrNothingAcrossLines:88->reserve:33 UnsupportedOperation not implemented yet (TDD red)
  ... (17 errors in total)
```

Red for the right reason: the Spring context, Flyway migrations, security chain and fixtures all worked; only the
behaviour was missing.

## Step 2 - first implementation (still RED, a design assumption was wrong)

The first implementation claimed the order's reservation slot with the PostgreSQL idiom
`INSERT ... ON CONFLICT (order_id) DO NOTHING` (native query). It was never going to be green on the development
database, because the contract requires the **same SQL on H2 (PostgreSQL mode) and PostgreSQL**:

```
Tests run: 33, Failures: 0, Errors: 32, Skipped: 0
org.springframework.dao.InvalidDataAccessResourceUsageException: could not prepare statement
  [Syntax error in SQL statement "INSERT INTO inventory_reservations (id, order_id, status, expires_at, created_at,
   updated_at, version) VALUES (?, ?, 'RESERVED', ?, ?, ?, 0) [*]ON CONFLICT (order_id) DO NOTHING"; ...]
```

(H2 2.3's PostgreSQL mode rejects `ON CONFLICT`. 32 of the 33 executions of the two classes failed; the remaining one is
the pure validation test that throws before any SQL.)

## Step 3 - the fix (GREEN)

A portable replacement, `ReservationClaims`: a plain `INSERT` inside a **JDBC savepoint**, using `JdbcTemplate` on the
transaction's connection:

* a concurrent claimer blocks on the unique index until the first transaction commits or rolls back (H2 and PostgreSQL
  alike), so a failed claim always finds the winner's committed row;
* `rollback(savepoint)` after the unique violation keeps the surrounding transaction usable (PostgreSQL would otherwise
  answer "current transaction is aborted");
* plain JDBC rather than JPA, because a failed JPA statement marks the Hibernate transaction rollback-only and would
  poison the Kafka processed-event transaction that wraps the command.

```
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in ReservationConcurrencyTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 -- in ReservationServiceTest
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

All three strategies passed the same suite, including 50 threads vs 10 units and the opposite-order deadlock test, on H2.

## Step 4 - more tests, more findings

| Finding | How a test exposed it | Fix |
|---|---|---|
| `{"delta":1.5,...}` was accepted and silently truncated to `1` (Jackson `ACCEPT_FLOAT_AS_INT` default) | `InventoryApiTest.adjustValidationAndAuthorization` expected 400, got 200 | `spring.jackson.deserialization.accept-float-as-int=false` |
| Re-reading a reservation after losing a guarded UPDATE returned the stale, already-managed entity | designed while writing `ReservationLifecycleTest.commitRacingTheExpiryJob...` (JPQL queries return the cached instance) | `fresh()` = flush + clear + re-query in the lifecycle service |
| Spring Boot tests disable the Prometheus exporter, so MockMvc answered `/actuator/prometheus` with a problem+json 404 | `ActuatorAndDocsTest` first version | meters asserted through the `MeterRegistry` in the shared context; the real scrape text is asserted in `InventoryKafkaIT` (`@AutoConfigureObservability`) |
| (test code) Mockito `UnfinishedStubbing` when a stub was built from a nested mock call; `assertThat(tx.execute(..))` ambiguity | compile / first run | locals before stubbing; typed locals |

## Step 5 - the same suite against a real PostgreSQL

H2 only approximates PostgreSQL's locking, so `PostgresScenarios` (50 threads vs 10 units, opposite orders, concurrent
duplicates, lifecycle, savepoint claim, constraints, search) runs against PostgreSQL 16 in two ways:

* `InventoryPostgresIT` - Testcontainers, `@Testcontainers(disabledWithoutDocker = true)`: **skipped** on machines
  without Docker;
* `InventoryLocalPostgresIT` - any reachable PostgreSQL via `OMS_TEST_PG_URL/USER/PASSWORD`. In this session it ran
  against a real **PostgreSQL 16.4** started from the Windows binaries of the `io.zonky.test.postgres` artifact that was
  already in `~/.m2` (SHA-1 verified against the stored `.sha1`): `Tests run: 16, Failures: 0, Errors: 0` for all three
  strategies, twice.

## Mutation check: does the deadlock test really test anything?

A green deadlock test proves little unless it can fail. With the productId sorting in `ReservationService.normalize`
temporarily replaced by insertion order (`TreeMap` -> `LinkedHashMap`), the opposite-order test run on H2 gave:

```
[ERROR] Tests run: 3, Failures: 0, Errors: 3 -- in ReservationConcurrencyTest   (oppositeItemOrdersDoNotDeadlock, atomic/pessimistic/optimistic)
org.springframework.dao.CannotAcquireLockException: JDBC exception executing SQL
  [update stock s1_0 set reserved=(s1_0.reserved+cast(? as integer)),version=(s1_0.version+1),updated_at=? where ...]
  [Deadlock detected. The current transaction was rolled back. Details: "stock"; ...]
Caused by: org.h2.mvstore.MVStoreException: Transaction 17 has been chosen as a deadlock victim.
```

All three strategies deadlock without the global lock order; the change was reverted and the suite is green again. The
other two safety nets were reasoned about but NOT mutation-tested: the status guards on the lifecycle UPDATEs
(`commitRacingTheExpiryJob...`) and the `ck_stock_reserved_le_on_hand` CHECK constraint (asserted directly in
`InventoryDbTest.checkConstraintsStopOversellingAndNegativeStockAtTheDatabaseLevel`).
