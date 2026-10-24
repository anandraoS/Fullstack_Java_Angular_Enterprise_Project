package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.inventory.config.InventoryProperties;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Optimistic read-modify-write: read {@code (on_hand, reserved, version)} (projection, always fresh), decide in Java,
 * then write with {@code UPDATE ... WHERE product_id = :id AND version = :seen} (compare-and-set). 0 rows = somebody
 * changed the row in between: count a conflict and redo the read, at most {@code oms.inventory.optimistic-max-retries}
 * times per line (bounded, with randomized backoff), then {@link ReservationContentionException}.
 * <p>
 * The CAS is expressed in SQL (not via a failing entity flush) on purpose: a failed Hibernate flush poisons the
 * persistence context and the surrounding transaction (which also carries the processed-event marker), whereas a
 * 0-row CAS can simply be retried inside the same transaction. Cheap when contention is low; under a hot row most
 * attempts are wasted work (see conflicts counter and the benchmark notes).
 */
@Component
public class OptimisticVersionReservationStrategy implements ReservationStrategy {

    public static final String NAME = "optimistic";

    private final StockRepository stock;
    private final ShortageCalculator shortages;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;
    private final Clock clock;

    public OptimisticVersionReservationStrategy(StockRepository stock, ShortageCalculator shortages,
                                                InventoryProperties props, InventoryMetrics metrics, Clock clock) {
        this.stock = stock;
        this.shortages = shortages;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result tryReserve(List<QuantityItem> lines) {
        Instant now = clock.instant();
        List<QuantityItem> taken = new ArrayList<>();
        boolean complete = true;
        for (QuantityItem line : lines) {
            if (reserveLine(line, now)) {
                taken.add(line);
            } else {
                complete = false;
                break;
            }
        }
        if (complete) {
            return Result.ok();
        }
        for (int i = taken.size() - 1; i >= 0; i--) {
            QuantityItem t = taken.get(i);
            if (stock.release(t.productId(), t.quantity(), now) != 1) {
                throw new IllegalStateException("Compensation failed for product " + t.productId());
            }
        }
        return Result.short_(shortages.shortages(lines));
    }

    /** @return true if reserved, false if insufficient; throws when contention exceeds the retry bound. */
    private boolean reserveLine(QuantityItem line, Instant now) {
        int max = Math.max(1, props.getOptimisticMaxRetries());
        for (int attempt = 1; attempt <= max; attempt++) {
            StockRepository.Snapshot snap = stock.snapshot(line.productId()).orElse(null);
            if (snap == null || snap.getOnHand() - snap.getReserved() < line.quantity()) {
                return false;
            }
            if (stock.compareAndReserve(line.productId(), line.quantity(), snap.getVersion(), now) == 1) {
                return true;
            }
            metrics.conflict();
            LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(50_000L, 500_000L * attempt));
        }
        throw new ReservationContentionException("Too many concurrent updates for product " + line.productId()
                + " (gave up after " + max + " attempts)");
    }
}
