# ADR-0006: Concurrency control for stock reservation

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

Many orders compete for the same `stock` rows. Overselling is a correctness bug; long locks are a throughput bug. Two kinds of writers exist: the reservation hot path (many, short) and admin stock edits (rare, human speed).

## Decision drivers

* Never reserve more than `on_hand - reserved`.
* No deadlocks on multi-line orders (up to 50 lines).
* Throughput under contention on popular SKUs.
* Reservation must be all-or-nothing per order.

## Considered options

1. Atomic conditional UPDATE (compare in the WHERE clause).
2. Pessimistic locking: `SELECT ... FOR UPDATE`, check in Java, then update.
3. Optimistic locking with `@Version`, retry on conflict.

## Decision outcome

Hot path uses option 1:

```sql
UPDATE stock
   SET reserved = reserved + :q, version = version + 1
 WHERE product_id = :id AND on_hand - reserved >= :q
```

Affected rows = 0 means insufficient stock; the whole reservation transaction rolls back and `InventoryReservationFailed` (with shortages) is emitted. The database serializes concurrent updates on the same row and re-evaluates the predicate against the committed value, so no application-level lock or retry loop is needed. Lines are processed in ascending `productId` order so two orders touching overlapping products acquire row locks in the same order and cannot deadlock. One transaction covers the reservation row, items, stock updates, `stock_movements` and the outbox event.

Admin stock edits use option 3: `stock.version` as JPA `@Version`; on `ObjectOptimisticLockingFailureException` the service retries a bounded number of times, then returns 409 `CONFLICT`. A result below `reserved` is rejected. Optimistic locking suits rare writes where a lost update must be detected rather than prevented.

Option 2 is kept as a selectable variant (`PessimisticLockReservationStrategy`, exercised by `ReservationConcurrencyTest` on H2 for every strategy), not the default. A strategy benchmark exists (`StrategyBenchmarkTest`/`BenchmarkRunner` in inventory-service, opt-in and skipped by default) and `performance/oversell.js` (k6) targets the oversell scenario; neither produced numbers (k6 never run, PostgreSQL never run), so no measured comparison exists.

### Consequences

* Good: single round trip, no read-then-write race, no lock held across application code, deadlock-free by ordering, the invariant lives in one SQL predicate.
* Bad: logic in SQL is less portable to non-relational stores and less visible than Java; the `version` column is bumped by the hot path too, so an admin edit racing with reservations can see optimistic conflicts (acceptable, retried); same-row contention still serializes on very hot SKUs; H2 `MODE=PostgreSQL` row-lock behaviour differs slightly from PostgreSQL, so contention tests that matter need Testcontainers (skipped where Docker is absent).

## Alternatives rejected

* Pessimistic as default: holds row locks while Java checks and while the transaction continues; amplifies contention and deadlock risk; needs the same sorted-order discipline anyway.
* Optimistic on the hot path: under contention most attempts fail and retry, wasting work and producing retry storms on popular SKUs.

## Links

* Contract section 7 (reservation lifecycle); inventory-service under `services/inventory-service`
* ADR-0005, ADR-0004
