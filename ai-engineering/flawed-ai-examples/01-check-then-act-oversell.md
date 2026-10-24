# 01 Check-then-act overselling race

Code: `java/src/main/java/com/enterprise/oms/aiexamples/Ex01Oversell.java`
Test: `Ex01OversellTest` (3 tests)

## Prompt that produces it
"Write `reserve(productId, qty)`: if enough stock is available, increase reserved."

## Flawed code
```java
public boolean reserve(int quantity) {
    if (row.available() >= quantity) {   // SELECT on_hand - reserved
        row.addReserved(quantity);       // UPDATE reserved = reserved + q
        return true;
    }
    return false;
}
```
In JPA form it is `Stock s = repo.findById(id); if (s.available() >= q) { s.setReserved(s.getReserved() + q); }`,
which also loses updates because the second writer overwrites the first's `reserved` value.

## Why it is risky here
Two checkout requests for the last unit both read `available = 1`, both pass the `if`, both write. The system promises
a customer stock it does not have; the order is later rejected or, worse, fulfilled and backordered. It only happens
under concurrency, so single-threaded unit tests and manual testing never show it. Seed data makes the scenario easy
to hit on purpose: SKU-1019 has stock 3 (contract section 9).

## Corrected code
```java
public boolean reserve(int quantity) {
    return row.addReservedIfAvailable(quantity) == 1;   // one statement; 0 affected rows == insufficient
}
// SQL form used by inventory-service (contract section 7):
// UPDATE stock SET reserved = reserved + :q, version = version + 1
//  WHERE product_id = :id AND on_hand - reserved >= :q
```
Multi-line reservations update rows in sorted `productId` order inside one transaction (all-or-nothing, no deadlock
between two orders touching the same products in opposite order). Trade-offs vs `SELECT ... FOR UPDATE` and `@Version`
are in `docs/adr/0006-concurrency-control.md`.

## Regression test
`flawedVersionOversellsWhenTwoRequestsInterleave` forces both threads through the check before either writes (barrier)
and asserts `accepted == 2` and `reserved > onHand`: the bug manifests. `fixedVersionNeverOversellsUnderSameInterleaving`
runs the same interleaving and asserts exactly one winner. `fixedVersionStressNeverExceedsOnHand` runs 16 threads x 20
attempts against 25 units and asserts exactly 25 accepted.

Service-level counterpart (exists in `services/inventory-service`): `ReservationConcurrencyTest` (for every strategy:
`fiftyThreadsCompeteForTenUnitsAndExactlyTenAreReserved`, `oppositeItemOrdersDoNotDeadlock`, ...) on H2, plus the live run
`tests/e2e/oversell-live.mjs` (30 parallel orders, stock 3: 3 FULFILLED, 27 REJECTED). The PostgreSQL twin
(`InventoryPostgresIT`) is skipped without Docker; mapped in `ai-engineering/specs/features/inventory-reservation.md`.

## Review prompt to catch it
"Show me the single statement that makes the stock check and the stock write atomic. If there are two statements, where
is the lock?"
