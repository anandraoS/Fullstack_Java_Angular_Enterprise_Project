package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DEFAULT strategy: {@code UPDATE stock SET reserved = reserved + :q, version = version + 1
 * WHERE product_id = :id AND on_hand - reserved >= :q}; 0 affected rows = insufficient stock.
 * <p>
 * No read, no explicit lock, no retry: the row lock taken by the UPDATE serializes writers and the predicate is
 * re-evaluated against the latest committed row after waiting, so overselling is impossible by construction.
 * Lines are processed in ascending productId order so two reservations with opposite item orders cannot deadlock.
 * All-or-nothing: lines already taken are given back (we still hold their row locks, so this cannot fail or race).
 */
@Component
public class AtomicUpdateReservationStrategy implements ReservationStrategy {

    public static final String NAME = "atomic";

    private final StockRepository stock;
    private final ShortageCalculator shortages;
    private final Clock clock;

    public AtomicUpdateReservationStrategy(StockRepository stock, ShortageCalculator shortages, Clock clock) {
        this.stock = stock;
        this.shortages = shortages;
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
            if (stock.tryReserve(line.productId(), line.quantity(), now) == 1) {
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
}
