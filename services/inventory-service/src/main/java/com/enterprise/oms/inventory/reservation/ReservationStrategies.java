package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.inventory.config.InventoryProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of the strategies; the active one is chosen by {@code oms.inventory.reservation-strategy} at startup
 * (unknown name = startup failure, never a silent fallback). {@link #select(String)} lets tests and benchmarks run
 * every strategy against the same context.
 */
@Component
public class ReservationStrategies {

    private final Map<String, ReservationStrategy> byName;
    private volatile ReservationStrategy active;

    public ReservationStrategies(List<ReservationStrategy> strategies, InventoryProperties props) {
        this.byName = strategies.stream().collect(Collectors.toUnmodifiableMap(
                s -> s.name().toLowerCase(Locale.ROOT), Function.identity()));
        this.active = lookup(props.getReservationStrategy());
    }

    public ReservationStrategy active() {
        return active;
    }

    /** Switches the active strategy (tests / benchmarks). Not exposed over HTTP. */
    public void select(String name) {
        this.active = lookup(name);
    }

    public ReservationStrategy get(String name) {
        return lookup(name);
    }

    private ReservationStrategy lookup(String name) {
        ReservationStrategy s = name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
        if (s == null) {
            throw new IllegalStateException("Unknown oms.inventory.reservation-strategy '" + name
                    + "', expected one of " + byName.keySet());
        }
        return s;
    }
}
