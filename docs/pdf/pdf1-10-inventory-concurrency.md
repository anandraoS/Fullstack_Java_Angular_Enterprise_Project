## The problem

Two customers order the last three units at the same instant. A naive implementation reads the available quantity, compares it in Java, then writes the new reserved count. Both requests read the same value and both write, so more units are promised than exist (a lost update). The code-review exercise `docs/code-review/01-oversell-race.md` and the flawed-AI example `ai-engineering/flawed-ai-examples/01-check-then-act-oversell.md` show exactly this bug and its fix.

## The model

Stock is split into `on_hand` (physical units) and `reserved` (promised to orders that are not shipped yet). Available units are `on_hand - reserved`. Every order owns exactly one row in `inventory_reservations` (unique `order_id`) with a lifecycle `RESERVED`, `COMMITTED`, `RELEASED`, `EXPIRED` or `FAILED`.

| Event | Stock effect | Reservation status |
|---|---|---|
| `ReserveInventory` succeeds | `reserved += q` | `RESERVED` (TTL, default 900 s) |
| `ReserveInventory` short of stock | none (all-or-nothing) | `FAILED` |
| `OrderConfirmed` | none | `COMMITTED`, TTL cleared |
| `FulfillmentCompleted` | `on_hand -= q`, `reserved -= q` | stays `COMMITTED`, `shipped_at` set |
| `ReleaseInventory` or `OrderCancelled` | `reserved -= q` | `RELEASED` |
| TTL elapsed while `RESERVED` | `reserved -= q` | `EXPIRED`, `InventoryReleased` event |

## Strategy 1 (default): atomic conditional update

```sql
UPDATE stock SET reserved = reserved + :q, version = version + 1
WHERE product_id = :id AND on_hand - reserved >= :q
```

Zero affected rows means insufficient stock. The row lock taken by the UPDATE serialises concurrent writers; after waiting, the predicate is re-evaluated against the latest committed row, so overselling is impossible by construction. No read, no explicit lock, no retry loop.

{{code:services/inventory-service/src/main/java/com/enterprise/oms/inventory/reservation/AtomicUpdateReservationStrategy.java|public Result tryReserve|24|java}}

Lines are processed in ascending product id order so two reservations with opposite item order cannot deadlock. If a later line fails, earlier lines are given back; this cannot fail or race because we still hold their row locks.

## Strategy 2 and 3

| Strategy | Mechanism | Strength | Weakness |
|---|---|---|---|
| `atomic` (default) | Conditional UPDATE | Simplest, fastest under contention, no retries | Business rule lives in SQL |
| `pessimistic` | `SELECT ... FOR UPDATE` (PESSIMISTIC_WRITE) in sorted order, then update | Easy to reason about, allows richer checks in Java | Holds locks longer, deadlock risk if order is not sorted |
| `optimistic` | `@Version` check, retry on conflict | No lock held while thinking | Retry storms on hot rows, needs bounded retries |

`oms.inventory.reservation-strategy` selects one (`OMS_RESERVATION_STRATEGY`). All three have identical behaviour; the inventory README records a benchmark of their contention behaviour.

## Defence in depth

* Database CHECK constraints in `services/inventory-service/src/main/resources/db/migration/V10__inventory_schema.sql`: both counters non-negative and `reserved <= on_hand`. If application code ever has a bug, the database refuses the write.
* Unique `order_id` makes a duplicate `ReserveInventory` return the original outcome and re-emit the same event, so redelivery is harmless.
* A release for an unknown order writes a `RELEASED` tombstone so a late or duplicate reserve can never take stock.
* `OrderConfirmed` arriving after expiry is not re-reserved; it is counted in `oms.inventory.reservation.late_commit` and left to reconciliation. Expiry versus confirm is decided by guarded UPDATEs: exactly one wins.
* Admin stock edits use optimistic `version` and reject a result below `reserved`.

## Evidence

Concurrency tests live under `services/inventory-service/src/test`; their execution status for the current build is in the evidence section of this document. `performance/oversell.js` is a k6 script that hammers one product; whether it was run is stated in the validation report, not assumed.
