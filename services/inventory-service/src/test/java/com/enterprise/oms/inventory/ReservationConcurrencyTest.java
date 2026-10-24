package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same concurrency suite for every strategy: no overselling, never negative, no deadlock with opposite item
 * orders, concurrent duplicate commands create one reservation, and the ledger always adds up.
 */
class ReservationConcurrencyTest extends AbstractInventoryTest {

    @Autowired ReservationService reservations;
    @Autowired ReservationStrategies strategies;

    /** Runs all tasks at the same instant on {@code tasks.size()} threads and returns their results (fails on any exception). */
    private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));   // a deadlock would surface here as a timeout
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private ReservationOutcome reserve(UUID order, QuantityItem... items) {
        return reservations.reserve(order, List.of(items), null, "corr-conc", null);
    }

    private int heldBy(UUID productId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity), 0) FROM inventory_reservation_items i "
                + "JOIN inventory_reservations r ON r.id = i.reservation_id "
                + "WHERE i.product_id = ? AND r.status IN ('RESERVED','COMMITTED')", Integer.class, productId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void fiftyThreadsCompeteForTenUnitsAndExactlyTenAreReserved(String strategy) throws Exception {
        strategies.select(strategy);
        UUID p = newProduct(10);
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID order = UUID.randomUUID();
            tasks.add(() -> reserve(order, item(p, 1)));
        }

        List<ReservationOutcome> outcomes = race(tasks);

        long reserved = outcomes.stream().filter(o -> o.status() == ReservationStatus.RESERVED).count();
        long failed = outcomes.stream().filter(ReservationOutcome::failed).count();
        assertThat(reserved).as("[%s] reserved", strategy).isEqualTo(10);
        assertThat(failed).as("[%s] failed", strategy).isEqualTo(40);
        assertThat(reserved(p)).isEqualTo(10);
        assertThat(onHand(p)).isEqualTo(10);
        assertThat(heldBy(p)).as("ledger equals stock.reserved").isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void multiUnitOrdersNeverOversellAndNeverGoNegative(String strategy) throws Exception {
        strategies.select(strategy);
        UUID p = newProduct(25);
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            UUID order = UUID.randomUUID();
            int qty = 1 + (i % 4);                       // 1..4 units per order
            tasks.add(() -> reserve(order, item(p, qty)));
        }

        race(tasks);

        assertThat(reserved(p)).isBetween(0, 25);
        assertThat(heldBy(p)).isEqualTo(reserved(p));
        assertThat(onHand(p) - reserved(p)).isGreaterThanOrEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void oppositeItemOrdersDoNotDeadlock(String strategy) throws Exception {
        strategies.select(strategy);
        UUID a = newProduct(1000);
        UUID b = newProduct(1000);
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID o1 = UUID.randomUUID();
            UUID o2 = UUID.randomUUID();
            tasks.add(() -> reserve(o1, item(a, 1), item(b, 1)));   // A then B
            tasks.add(() -> reserve(o2, item(b, 1), item(a, 1)));   // B then A (classic deadlock recipe)
        }

        List<ReservationOutcome> outcomes = race(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == ReservationStatus.RESERVED);
        assertThat(reserved(a)).isEqualTo(40);
        assertThat(reserved(b)).isEqualTo(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void concurrentDuplicateReserveCommandsCreateOneReservation(String strategy) throws Exception {
        strategies.select(strategy);
        UUID p = newProduct(100);
        UUID order = UUID.randomUUID();
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> reserve(order, item(p, 3)));
        }

        List<ReservationOutcome> outcomes = race(tasks);

        assertThat(reservationCount(order)).isEqualTo(1);
        assertThat(reserved(p)).as("stock held exactly once").isEqualTo(3);
        assertThat(outcomes.stream().filter(o -> !o.duplicate()).count()).isEqualTo(1);
        assertThat(outcomes.stream().map(ReservationOutcome::reservationId).distinct().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void contendedMultiProductOrdersKeepTheLedgerConsistent(String strategy) throws Exception {
        strategies.select(strategy);
        UUID a = newProduct(12);
        UUID b = newProduct(7);
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            UUID order = UUID.randomUUID();
            List<QuantityItem> lines = new ArrayList<>(List.of(item(a, 1 + i % 3), item(b, 1 + i % 2)));
            if (i % 2 == 0) {
                Collections.reverse(lines);
            }
            tasks.add(() -> reservations.reserve(order, lines, null, "c", null));
        }

        race(tasks);

        assertThat(reserved(a)).isLessThanOrEqualTo(12);
        assertThat(reserved(b)).isLessThanOrEqualTo(7);
        assertThat(heldBy(a)).isEqualTo(reserved(a));
        assertThat(heldBy(b)).isEqualTo(reserved(b));
    }
}
