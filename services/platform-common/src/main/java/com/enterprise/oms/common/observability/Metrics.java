package com.enterprise.oms.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.ToDoubleFunction;

/** Tiny Micrometer helpers for the metric names fixed by the contract (section 7). */
public final class Metrics {

    private Metrics() {
    }

    public static Counter counter(MeterRegistry registry, String name, String... tags) {
        return registry.counter(name, tags);
    }

    public static Timer timer(MeterRegistry registry, String name, String... tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(registry);
    }

    /**
     * Registers a gauge that reads {@code fn(owner)} on every scrape. Micrometer holds {@code owner} weakly, so pass
     * a long-lived object (a bean), never a lambda or method reference created inline. Exceptions yield NaN.
     */
    public static <T> void gauge(MeterRegistry registry, String name, T owner, ToDoubleFunction<T> fn,
                                 String... tags) {
        Gauge.builder(name, owner, o -> {
            try {
                return fn.applyAsDouble(o);
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).tags(tags).register(registry);
    }
}
