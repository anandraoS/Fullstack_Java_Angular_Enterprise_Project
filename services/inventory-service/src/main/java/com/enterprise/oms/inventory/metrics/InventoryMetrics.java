package com.enterprise.oms.inventory.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Inventory business metrics (the {@code service} tag is added globally):
 * <ul>
 *   <li>{@code oms.inventory.reservations{outcome}} - reserved | failed | duplicate | committed | released | expired | shipped;</li>
 *   <li>{@code oms.inventory.reservation.conflicts} - lost optimistic races (CAS / @Version) that were retried;</li>
 *   <li>{@code oms.inventory.reservation.duration{strategy,outcome}} - timer with percentile histogram;</li>
 *   <li>{@code oms.inventory.reservation.late_commit} - OrderConfirmed that found its reservation already released/expired.</li>
 * </ul>
 */
@Component
public class InventoryMetrics {

    private final MeterRegistry registry;
    private final Counter conflicts;
    private final Counter lateCommit;
    private final ConcurrentMap<String, Counter> outcomes = new ConcurrentHashMap<>();

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.conflicts = Counter.builder("oms.inventory.reservation.conflicts")
                .description("Optimistic-lock conflicts (retried)").register(registry);
        this.lateCommit = Counter.builder("oms.inventory.reservation.late_commit")
                .description("OrderConfirmed for a reservation that is no longer RESERVED").register(registry);
        for (String o : new String[]{"reserved", "failed", "duplicate", "committed", "released", "expired", "shipped"}) {
            outcome(o);
        }
    }

    public void conflict() {
        conflicts.increment();
    }

    public void lateCommit() {
        lateCommit.increment();
    }

    public void reservation(String outcome) {
        outcome(outcome).increment();
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample, String strategy, String outcome) {
        sample.stop(Timer.builder("oms.inventory.reservation.duration").tag("strategy", strategy)
                .tag("outcome", outcome).publishPercentileHistogram().register(registry));
    }

    public double conflictCount() {
        return conflicts.count();
    }

    public double outcomeCount(String outcome) {
        return outcome(outcome).count();
    }

    private Counter outcome(String outcome) {
        return outcomes.computeIfAbsent(outcome, o -> Counter.builder("oms.inventory.reservations")
                .tag("outcome", o).description("Reservation lifecycle outcomes").register(registry));
    }
}
