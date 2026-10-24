package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.ReleaseReasons;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationLifecycleService.Result;
import com.enterprise.oms.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Commit / release / ship / expire against the real schema (H2 PostgreSQL mode). */
class ReservationLifecycleTest extends AbstractInventoryTest {

    @Autowired ReservationService reservations;
    @Autowired ReservationLifecycleService lifecycle;
    @Autowired InventoryMetrics metrics;

    private ReservationOutcome reserve(UUID order, UUID product, int qty) {
        return reservations.reserve(order, List.of(item(product, qty)), null, "corr-life", null);
    }

    private String expiresAt(UUID order) {
        return jdbc.queryForObject("SELECT CAST(expires_at AS VARCHAR) FROM inventory_reservations WHERE order_id = ?",
                String.class, order);
    }

    private int movements(UUID product, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type = ?",
                Integer.class, product, type);
    }

    private void expireNow(UUID order) {
        jdbc.update("UPDATE inventory_reservations SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), order);
    }

    @Test
    void commitClearsTheTtlKeepsTheStockHeldAndIsIdempotent() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 4);

        assertThat(lifecycle.commit(order)).isEqualTo(Result.APPLIED);
        assertThat(lifecycle.commit(order)).isEqualTo(Result.NO_OP);

        assertThat(reservationStatus(order)).isEqualTo("COMMITTED");
        assertThat(expiresAt(order)).isNull();
        assertThat(reserved(p)).isEqualTo(4);
    }

    @Test
    void commitWithoutAReservationIsIgnored() {
        assertThat(lifecycle.commit(UUID.randomUUID())).isEqualTo(Result.NOT_FOUND);
    }

    @Test
    void releaseGivesStockBackEmitsInventoryReleasedAndWritesTheLedger() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 4);

        Result r = lifecycle.release(order, ReleaseReasons.CANCELLATION, true, "corr", null);

        assertThat(r).isEqualTo(Result.APPLIED);
        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        assertThat(reserved(p)).isZero();
        assertThat(onHand(p)).isEqualTo(10);
        assertThat(outboxCount(order, EventTypes.INVENTORY_RELEASED)).isEqualTo(1);
        assertThat(outboxPayloads(order, EventTypes.INVENTORY_RELEASED).get(0)).contains("CANCELLATION");
        assertThat(movements(p, "RESERVE")).isEqualTo(1);
        assertThat(movements(p, "RELEASE")).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndTheCommandIsAnsweredAgainButTheEventStaysSilent() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 4);
        lifecycle.release(order, ReleaseReasons.CANCELLATION, true, "corr", null);

        Result again = lifecycle.release(order, ReleaseReasons.CANCELLATION, true, "corr", null);
        Result fromOrderCancelled = lifecycle.release(order, ReleaseReasons.CANCELLATION, false, "corr", null);

        assertThat(again).isEqualTo(Result.NO_OP);
        assertThat(fromOrderCancelled).isEqualTo(Result.NO_OP);
        assertThat(reserved(p)).as("stock is never given back twice").isZero();
        assertThat(outboxCount(order, EventTypes.INVENTORY_RELEASED)).isEqualTo(2);   // original + re-answer of the command
    }

    @Test
    void releasingACommittedReservationWorks() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 3);
        lifecycle.commit(order);

        assertThat(lifecycle.release(order, ReleaseReasons.FULFILLMENT_FAILURE, true, "c", null)).isEqualTo(Result.APPLIED);

        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        assertThat(reserved(p)).isZero();
    }

    @Test
    void releaseBeforeAnyReservationLeavesATombstoneThatBlocksALaterReserve() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();

        Result r = lifecycle.release(order, ReleaseReasons.CANCELLATION, true, "c", null);
        ReservationOutcome late = reserve(order, p, 5);

        assertThat(r).isEqualTo(Result.NOT_FOUND);
        assertThat(outboxCount(order, EventTypes.INVENTORY_RELEASED)).isGreaterThanOrEqualTo(1);
        assertThat(late.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(late.duplicate()).isTrue();
        assertThat(reserved(p)).isZero();
        assertThat(reservationCount(order)).isEqualTo(1);
    }

    @Test
    void shipTakesTheGoodsOutOfOnHandAndReservedExactlyOnce() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 3);
        lifecycle.commit(order);

        assertThat(lifecycle.ship(order)).isEqualTo(Result.APPLIED);
        assertThat(lifecycle.ship(order)).isEqualTo(Result.NO_OP);

        assertThat(onHand(p)).isEqualTo(7);
        assertThat(reserved(p)).isZero();
        assertThat(movements(p, "SHIP")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shipped_at IS NOT NULL FROM inventory_reservations WHERE order_id = ?",
                Boolean.class, order)).isTrue();
    }

    @Test
    void shippedGoodsCannotBeReleased() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 3);
        lifecycle.commit(order);
        lifecycle.ship(order);

        assertThat(lifecycle.release(order, ReleaseReasons.CANCELLATION, true, "c", null)).isEqualTo(Result.REJECTED);
        assertThat(onHand(p)).isEqualTo(7);
        assertThat(reserved(p)).isZero();
    }

    @Test
    void shipWithoutAReservationOrAfterReleaseShipsNothing() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 3);
        lifecycle.release(order, ReleaseReasons.CANCELLATION, false, "c", null);

        assertThat(lifecycle.ship(order)).isEqualTo(Result.REJECTED);
        assertThat(lifecycle.ship(UUID.randomUUID())).isEqualTo(Result.NOT_FOUND);
        assertThat(onHand(p)).isEqualTo(10);
    }

    @Test
    void expiryReleasesOverdueReservationsAndEmitsInventoryReleasedWithReasonExpired() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 4);
        expireNow(order);

        int expired = lifecycle.expireDue();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(reservationStatus(order)).isEqualTo("EXPIRED");
        assertThat(reserved(p)).isZero();
        assertThat(outboxPayloads(order, EventTypes.INVENTORY_RELEASED)).singleElement().asString().contains("EXPIRED");
        assertThat(movements(p, "EXPIRE")).isEqualTo(1);
        assertThat(lifecycle.expireDue()).as("a second scan finds nothing to do for this order").isZero();
        assertThat(outboxCount(order, EventTypes.INVENTORY_RELEASED)).isEqualTo(1);
    }

    @Test
    void expiryLeavesCommittedAndNotYetDueReservationsAlone() {
        UUID p = newProduct(10);
        UUID committed = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        reserve(committed, p, 2);
        reserve(pending, p, 3);
        lifecycle.commit(committed);
        jdbc.update("UPDATE inventory_reservations SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), committed);   // committed rows have no expiry; make sure it stays irrelevant

        lifecycle.expireDue();

        assertThat(reservationStatus(committed)).isEqualTo("COMMITTED");
        assertThat(reservationStatus(pending)).isEqualTo("RESERVED");
        assertThat(reserved(p)).isEqualTo(5);
    }

    @Test
    void aConfirmationThatArrivesAfterExpiryIsRejectedAndCounted() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reserve(order, p, 4);
        expireNow(order);
        lifecycle.expireDue();
        double lateBefore = lateCommits();

        Result r = lifecycle.commit(order);

        assertThat(r).isEqualTo(Result.REJECTED);
        assertThat(reservationStatus(order)).isEqualTo("EXPIRED");
        assertThat(reserved(p)).isZero();
        assertThat(lateCommits()).isEqualTo(lateBefore + 1);
    }

    private double lateCommits() {
        return metricsRegistryCount("oms.inventory.reservation.late_commit");
    }

    @Autowired io.micrometer.core.instrument.MeterRegistry registry;

    private double metricsRegistryCount(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void commitRacingTheExpiryJobEndsInExactlyOneConsistentState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 15; i++) {
                UUID p = newProduct(10);
                UUID order = UUID.randomUUID();
                reserve(order, p, 2);
                expireNow(order);
                CountDownLatch go = new CountDownLatch(1);
                Future<Result> commit = pool.submit(() -> {
                    go.await();
                    return lifecycle.commit(order);
                });
                Future<Integer> expire = pool.submit(() -> {
                    go.await();
                    return lifecycle.expireDue();
                });
                go.countDown();
                Result commitResult = commit.get(30, TimeUnit.SECONDS);
                expire.get(30, TimeUnit.SECONDS);

                String status = reservationStatus(order);
                if (status.equals("COMMITTED")) {
                    assertThat(commitResult).isEqualTo(Result.APPLIED);
                    assertThat(reserved(p)).as("committed keeps the stock held").isEqualTo(2);
                } else {
                    assertThat(status).isEqualTo("EXPIRED");
                    assertThat(commitResult).isEqualTo(Result.REJECTED);
                    assertThat(reserved(p)).as("expired gave the stock back").isZero();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
