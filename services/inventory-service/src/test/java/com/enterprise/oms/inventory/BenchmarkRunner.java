package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark of the three reservation strategies (OPT-IN: {@code -Doms.bench=true}). It measures the WHOLE
 * {@code ReservationService.reserve} transaction (claim, strategy, items, movements, audit, outbox rows), not just the
 * stock statement. Numbers are only comparable within one run on one machine/database; they are a demonstration of the
 * trade-offs, not a capacity statement.
 */
final class BenchmarkRunner {

    private static final int THREADS = 16;
    private static final int OPS_PER_THREAD = 150;

    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private final ReservationStrategies strategies;
    private final InventoryMetrics metrics;

    BenchmarkRunner(JdbcTemplate jdbc, ReservationService reservations, ReservationStrategies strategies,
                    InventoryMetrics metrics) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.strategies = strategies;
        this.metrics = metrics;
    }

    private enum Scenario {
        HOT_ROW("hot row: 1 product, 1 unit per order"),
        SPREAD("spread: 200 products, 1 unit per order"),
        HOT_PAIR_ORDERS("3-line orders over 10 hot products");

        final String label;

        Scenario(String label) {
            this.label = label;
        }
    }

    String run(String database) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("Reservation strategy benchmark on ").append(database).append(" (").append(THREADS)
                .append(" threads x ").append(OPS_PER_THREAD).append(" reserve() calls per cell, ")
                .append(Instant.now()).append(")\n");
        out.append(String.format("%-40s %-12s %10s %9s %9s %9s %10s%n", "scenario", "strategy", "ops/s", "p50 ms", "p95 ms",
                "max ms", "conflicts"));
        for (Scenario scenario : Scenario.values()) {
            for (String strategy : new String[]{"atomic", "pessimistic", "optimistic"}) {
                out.append(runCell(scenario, strategy));
            }
        }
        String text = out.toString();
        System.out.println(text);
        Path file = Path.of("target", "benchmarks", "strategy-benchmark-" + database.replaceAll("[^A-Za-z0-9]+", "-") + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("could not write " + file + ": " + e);
        }
        return text;
    }

    private String runCell(Scenario scenario, String strategy) throws Exception {
        strategies.select(strategy);
        int productCount = switch (scenario) {
            case HOT_ROW -> 1;
            case SPREAD -> 200;
            case HOT_PAIR_ORDERS -> 10;
        };
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            products.add(newProduct(1_000_000));
        }
        double conflictsBefore = metrics.conflictCount();
        long[] latenciesNanos = new long[THREADS * OPS_PER_THREAD];
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    List<QuantityItem> lines = switch (scenario) {
                        case HOT_ROW -> List.of(new QuantityItem(products.get(0), 1));
                        case SPREAD -> List.of(new QuantityItem(products.get(rnd.nextInt(productCount)), 1));
                        case HOT_PAIR_ORDERS -> {
                            int a = rnd.nextInt(productCount);
                            yield List.of(new QuantityItem(products.get(a), 1),
                                    new QuantityItem(products.get((a + 1) % productCount), 1),
                                    new QuantityItem(products.get((a + 2) % productCount), 1));
                        }
                    };
                    long start = System.nanoTime();
                    ReservationOutcome o = reservations.reserve(UUID.randomUUID(), lines, null, "bench", null);
                    latenciesNanos[thread * OPS_PER_THREAD + i] = System.nanoTime() - start;
                    if (!o.holdsStock()) {
                        throw new IllegalStateException("benchmark reservation unexpectedly failed: " + o.reason());
                    }
                }
                return null;
            }));
        }
        ready.await();
        long begin = System.nanoTime();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.MINUTES);
        }
        long elapsed = System.nanoTime() - begin;
        pool.shutdownNow();
        Arrays.sort(latenciesNanos);
        double opsPerSec = latenciesNanos.length / (elapsed / 1e9);
        return String.format("%-40s %-12s %10.0f %9.2f %9.2f %9.2f %10.0f%n", scenario.label, strategy, opsPerSec,
                latenciesNanos[latenciesNanos.length / 2] / 1e6, latenciesNanos[(int) (latenciesNanos.length * 0.95)] / 1e6,
                latenciesNanos[latenciesNanos.length - 1] / 1e6, metrics.conflictCount() - conflictsBefore);
    }

    private UUID newProduct(int onHand) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO products (id, sku, name, description, category, price, currency, active, created_at, updated_at, version) "
                + "VALUES (?, ?, 'bench', 'd', 'Bench', 5.00, 'USD', TRUE, ?, ?, 0)", id,
                "BM-" + id.toString().substring(0, 8).toUpperCase(), now, now);
        jdbc.update("INSERT INTO stock (product_id, on_hand, reserved, version, updated_at) VALUES (?, ?, 0, 0, ?)", id, onHand, now);
        return id;
    }
}
