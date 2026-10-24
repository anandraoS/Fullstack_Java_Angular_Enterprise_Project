package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;

import java.util.List;

/**
 * How stock is taken out of {@code available} for a reservation. Three interchangeable implementations demonstrate the
 * classic trade-offs (see service README and docs/database/inventory-explain-analyze.md):
 * <ul>
 *   <li>{@code atomic} - one conditional UPDATE per line (default; no read, no application lock);</li>
 *   <li>{@code pessimistic} - SELECT ... FOR UPDATE then write (serializes per row, simple to reason about);</li>
 *   <li>{@code optimistic} - read version, compare-and-set on version, bounded retry (cheap when contention is low).</li>
 * </ul>
 * <b>Contract</b> for every implementation: runs inside the caller's transaction (it never commits), receives lines
 * already merged per product, quantity &gt; 0 and sorted by productId ascending (the global lock order that rules out
 * deadlocks), is all-or-nothing (on a shortage it leaves NO partial hold behind), and reports every short line.
 */
public interface ReservationStrategy {

    /** Name used by {@code oms.inventory.reservation-strategy}. */
    String name();

    /** @param lines sorted by productId asc, distinct products, quantity &gt; 0 */
    Result tryReserve(List<QuantityItem> lines);

    /** Outcome of {@link #tryReserve}. {@code shortages} is empty on success. */
    record Result(boolean success, List<Shortage> shortages) {
        public static Result ok() {
            return new Result(true, List.of());
        }

        public static Result short_(List<Shortage> shortages) {
            return new Result(false, List.copyOf(shortages));
        }
    }
}
