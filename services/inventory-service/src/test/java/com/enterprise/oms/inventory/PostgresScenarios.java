package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.repo.ProductQueryRepository;
import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.inventory.service.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guarantees that must hold on a REAL PostgreSQL (row-level locking, READ COMMITTED, savepoints), shared by
 * {@link InventoryPostgresIT} (Testcontainers, needs Docker) and {@link InventoryLocalPostgresIT} (any reachable
 * PostgreSQL given by environment variables). H2 only approximates PostgreSQL's locking, so these tests are the
 * authoritative evidence for "no overselling".
 */
interface PostgresScenarios {

    JdbcTemplate jdbc();

    ReservationService reservations();

    ReservationLifecycleService lifecycle();

    ReservationStrategies strategies();

    ReservationClaims claims();

    ProductQueryRepository query();

    StockService stockService();

    PlatformTransactionManager txManager();

    com.enterprise.oms.inventory.metrics.InventoryMetrics metrics();

    default UUID newProduct(int onHand) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc().update("INSERT INTO products (id, sku, name, description, category, price, currency, active, created_at, updated_at, version) "
                + "VALUES (?, ?, 'pg test', 'd', 'PG', 5.00, 'USD', TRUE, ?, ?, 0)", id,
                "PG-" + id.toString().substring(0, 8).toUpperCase(), now, now);
        jdbc().update("INSERT INTO stock (product_id, on_hand, reserved, version, updated_at) VALUES (?, ?, 0, 0, ?)", id, onHand, now);
        return id;
    }

    default int reservedOf(UUID p) {
        return jdbc().queryForObject("SELECT reserved FROM stock WHERE product_id = ?", Integer.class, p);
    }

    default int onHandOf(UUID p) {
        return jdbc().queryForObject("SELECT on_hand FROM stock WHERE product_id = ?", Integer.class, p);
    }

    default <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return t.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    default void fiftyThreadsCompeteForTenUnitsOnPostgres(String strategy) throws Exception {
        strategies().select(strategy);
        UUID p = newProduct(10);
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID order = UUID.randomUUID();
            tasks.add(() -> reservations().reserve(order, List.of(new QuantityItem(p, 1)), null, "pg", null));
        }
        List<ReservationOutcome> outcomes = race(tasks);
        assertThat(outcomes.stream().filter(o -> o.status() == ReservationStatus.RESERVED).count()).isEqualTo(10);
        assertThat(outcomes.stream().filter(ReservationOutcome::failed).count()).isEqualTo(40);
        assertThat(reservedOf(p)).isEqualTo(10);
        assertThat(onHandOf(p)).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    default void oppositeItemOrdersDoNotDeadlockOnPostgres(String strategy) throws Exception {
        strategies().select(strategy);
        UUID a = newProduct(1000);
        UUID b = newProduct(1000);
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID o1 = UUID.randomUUID();
            UUID o2 = UUID.randomUUID();
            tasks.add(() -> reservations().reserve(o1, List.of(new QuantityItem(a, 1), new QuantityItem(b, 1)), null, "pg", null));
            tasks.add(() -> reservations().reserve(o2, List.of(new QuantityItem(b, 1), new QuantityItem(a, 1)), null, "pg", null));
        }
        assertThat(race(tasks)).allMatch(o -> o.status() == ReservationStatus.RESERVED);
        assertThat(reservedOf(a)).isEqualTo(40);
        assertThat(reservedOf(b)).isEqualTo(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    default void concurrentDuplicateCommandsCreateOneReservationOnPostgres(String strategy) throws Exception {
        strategies().select(strategy);
        UUID p = newProduct(100);
        UUID order = UUID.randomUUID();
        List<Callable<ReservationOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> reservations().reserve(order, List.of(new QuantityItem(p, 3)), null, "pg", null));
        }
        List<ReservationOutcome> outcomes = race(tasks);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM inventory_reservations WHERE order_id = ?", Integer.class, order)).isEqualTo(1);
        assertThat(reservedOf(p)).isEqualTo(3);
        assertThat(outcomes.stream().filter(o -> !o.duplicate()).count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    default void lifecycleOnPostgres(String strategy) {
        strategies().select(strategy);
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        reservations().reserve(order, List.of(new QuantityItem(p, 4)), null, "pg", null);
        lifecycle().commit(order);
        lifecycle().ship(order);
        assertThat(onHandOf(p)).isEqualTo(6);
        assertThat(reservedOf(p)).isZero();
    }

    @Test
    default void slotClaimSavepointKeepsThePostgresTransactionAliveAfterTheUniqueViolation() {
        UUID order = UUID.randomUUID();
        Instant now = Instant.now();
        Integer rows = new TransactionTemplate(txManager()).execute(status -> {
            assertThat(claims().claimReserved(UUID.randomUUID(), order, now.plusSeconds(60), now)).isTrue();
            assertThat(claims().claimReserved(UUID.randomUUID(), order, now.plusSeconds(60), now)).isFalse();
            // without the savepoint PostgreSQL would answer "current transaction is aborted" here
            return jdbc().queryForObject("SELECT COUNT(*) FROM inventory_reservations WHERE order_id = ?", Integer.class, order);
        });
        assertThat(rows).isEqualTo(1);
    }

    /** Opt-in: -Doms.bench=true (prints a table and writes target/benchmarks/). */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "oms.bench", matches = "true")
    default void benchmarkStrategiesOnPostgres() throws Exception {
        new BenchmarkRunner(jdbc(), reservations(), strategies(), metrics()).run("PostgreSQL");
    }

    @Test
    default void checkConstraintsHoldOnPostgres() {
        UUID p = newProduct(10);
        assertThatThrownBy(() -> jdbc().update("UPDATE stock SET reserved = 11 WHERE product_id = ?", p))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    default void adjustRejectsBelowReservedOnPostgres() {
        UUID p = newProduct(10);
        reservations().reserve(UUID.randomUUID(), List.of(new QuantityItem(p, 8)), null, "pg", null);
        assertThatThrownBy(() -> stockService().adjust(p, -3, "write off"))
                .isInstanceOf(com.enterprise.oms.common.error.ConflictException.class);
        stockService().adjust(p, -2, "write off");
        assertThat(onHandOf(p)).isEqualTo(8);
    }

    @Test
    default void searchWithWildcardEscapingAndPagingRunsOnPostgres() {
        newProduct(5);
        var none = query().search(new ProductQueryRepository.Filter("100%_", null, "PG", null, null, true), PageRequest.of(0, 5));
        assertThat(none.getContent()).isEmpty();
        var some = query().search(new ProductQueryRepository.Filter(null, "pg-", "PG", true, null, true), PageRequest.of(0, 5));
        assertThat(some.getTotalElements()).isPositive();
    }
}
