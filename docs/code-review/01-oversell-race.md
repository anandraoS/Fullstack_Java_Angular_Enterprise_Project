# 01 - Oversell race in stock reservation

Severity: **Critical** (sells stock that does not exist; breaks the invariant `on_hand - reserved >= 0`)

## PR title: "Reserve inventory for ReserveInventory command"

```diff
 // inventory-service: ReservationService (placeholder name)
 @Transactional
 public ReservationResult reserve(UUID orderId, List<Line> lines) {
+    for (Line line : lines) {
+        Stock s = stockRepository.findByProductId(line.productId()).orElseThrow();
+        if (s.getOnHand() - s.getReserved() < line.quantity()) {
+            return ReservationResult.insufficient(line.productId(), s.getOnHand() - s.getReserved());
+        }
+        s.setReserved(s.getReserved() + line.quantity());
+        stockRepository.save(s);
+    }
+    reservationRepository.save(Reservation.reserved(orderId, lines));
+    return ReservationResult.ok();
 }
```

## Reasoning

This is check-then-act. Two consumers (two partitions, or two instances) handle reservations for different orders wanting SKU-1019 (seed stock 3). At READ COMMITTED, the default on PostgreSQL:

| Time | Tx A (qty 2) | Tx B (qty 2) |
|---|---|---|
| t1 | reads on_hand 3, reserved 0 -> available 3, ok | |
| t2 | | reads on_hand 3, reserved 0 -> available 3, ok |
| t3 | writes reserved = 2 | |
| t4 | | writes reserved = 2 (overwrites; JPA writes the absolute value it computed) |
| t5 | commit | commit |

Result: two reservations of 2 recorded, `reserved` = 2, true demand 4, stock 3. Lost update. Nothing throws, and no metric changes. It is discovered when fulfillment cannot ship (see runbook `../runbooks/03-inventory-reservation-failures-under-concurrency.md`).

Why tests did not catch it: the PR test reserves twice sequentially. Sequential calls cannot interleave.

Secondary issues:

* Multi-line orders iterate in request order. Two orders with lines [A,B] and [B,A] lock in opposite order once a lock is added, producing deadlocks. Sort by productId.
* `save(s)` on a detached/managed entity with `@Version` would throw `ObjectOptimisticLockingFailureException` only on one of the two, giving a 500 or a redelivery storm instead of a clean `InventoryReservationFailed`.
* Partial reservation: if line 2 is insufficient after line 1 was updated, the method returns without throwing, so the transaction **commits** line 1's increment. All-or-nothing is required by the contract; return value is not a rollback.

## Fix (contract section 7: atomic conditional UPDATE)

```java
@Transactional
public ReservationResult reserve(UUID orderId, List<Line> lines) {
    Optional<Reservation> existing = reservationRepository.findByOrderId(orderId);   // unique order_id
    if (existing.isPresent()) return ReservationResult.from(existing.get());         // duplicate command: original outcome

    List<Line> sorted = lines.stream().sorted(comparing(Line::productId)).toList();
    List<Shortage> shortages = new ArrayList<>();
    for (Line l : sorted) {
        int rows = jdbc.update("""
            UPDATE stock SET reserved = reserved + :q, version = version + 1
             WHERE product_id = :id AND on_hand - reserved >= :q
            """, Map.of("q", l.quantity(), "id", l.productId()));
        if (rows == 0) shortages.add(shortageFor(l));
    }
    if (!shortages.isEmpty()) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();   // undo earlier lines
        return ReservationResult.insufficient(shortages);   // caller records the FAILED outcome in a NEW tx
    }
    reservationRepository.save(Reservation.reserved(orderId, sorted));
    return ReservationResult.ok();
}
```

Note: after `setRollbackOnly` the failed outcome (reservation row with status `FAILED` plus the `InventoryReservationFailed` outbox row) must be written in a separate transaction, or the shortage check should be done by throwing a dedicated exception that a wrapper turns into that second transaction. Either approach is acceptable; what matters is that no line increment survives a failed order.

## Regression tests

```java
// sketch: @SpringBootTest on H2 (MODE=PostgreSQL); the PostgreSQL twin extends AbstractPostgresIT and is skipped without Docker
@Test
void twentyContenders_stockOfThree_exactlyThreeSucceed() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(20);
    List<Future<Boolean>> f = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
        UUID orderId = UUID.randomUUID();
        f.add(pool.submit(() -> { start.await(); return service.reserve(orderId, List.of(new Line(SKU_1019, 1))).isOk(); }));
    }
    start.countDown();
    long ok = f.stream().filter(x -> { try { return x.get(15, SECONDS); } catch (Exception e) { throw new RuntimeException(e); } }).count();
    pool.shutdown();

    assertThat(ok).isEqualTo(3);
    assertThat(stockRow(SKU_1019).reserved()).isEqualTo(3);
}

@Test
void failedMultiLineOrder_leavesNoPartialReservation() {
    var r = service.reserve(UUID.randomUUID(), List.of(new Line(SKU_1001, 5), new Line(SKU_1020, 1))); // 1020 has stock 0
    assertThat(r.isOk()).isFalse();
    assertThat(stockRow(SKU_1001).reserved()).isZero();
}

@Test
void duplicateCommand_returnsOriginalOutcome_withoutDoubleCounting() {
    UUID orderId = UUID.randomUUID();
    service.reserve(orderId, List.of(new Line(SKU_1001, 2)));
    service.reserve(orderId, List.of(new Line(SKU_1001, 2)));
    assertThat(stockRow(SKU_1001).reserved()).isEqualTo(2);
}
```

## Reviewer checklist from this scenario

- [ ] Is the availability check part of the write statement?
- [ ] Is there a test that races, with exact numeric assertions?
- [ ] Are keys processed in a deterministic order?
- [ ] Does a failure roll back everything for that order?
- [ ] Is a duplicate command safe?
