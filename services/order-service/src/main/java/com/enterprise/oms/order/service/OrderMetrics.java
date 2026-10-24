package com.enterprise.oms.order.service;

import com.enterprise.oms.common.observability.Metrics;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Metrics of contract section 7 owned by order-service:
 * {@code oms.order.processing.duration} (timer with percentile histogram, tag outcome), {@code oms.saga.active}
 * (gauge), {@code oms.saga.compensations} (counter, tag reason); plus {@code oms.saga.alerts} (exhausted retries)
 * and {@code oms.saga.events.ignored} (stale/duplicate/unknown events the state machine refused).
 */
@Component
public class OrderMetrics {

    public static final String PROCESSING_TIMER = "oms.order.processing.duration";

    private final MeterRegistry registry;
    private final SagaInstanceRepository sagas;
    private volatile double activeCache;
    private volatile long activeCachedAt;

    public OrderMetrics(MeterRegistry registry, SagaInstanceRepository sagas) {
        this.registry = registry;
        this.sagas = sagas;
        Metrics.gauge(registry, "oms.saga.active", this, OrderMetrics::activeSagas);
        // pre-register so dashboards see the series before the first order completes
        for (OrderStatus s : List.of(OrderStatus.FULFILLED, OrderStatus.REJECTED, OrderStatus.CANCELLED,
                OrderStatus.FAILED)) {
            timer(s);
        }
    }

    private Timer timer(OrderStatus outcome) {
        return Timer.builder(PROCESSING_TIMER).description("Order lifetime from CREATED to a terminal status")
                .tag("outcome", outcome.name()).publishPercentileHistogram().register(registry);
    }

    /** Called once when an order reaches a terminal status. */
    public void orderFinished(OrderStatus terminal, Duration sinceCreated) {
        timer(terminal).record(sinceCreated.isNegative() ? Duration.ZERO : sinceCreated);
    }

    public void compensation(String reason) {
        registry.counter("oms.saga.compensations", "reason", reason).increment();
    }

    public void alert(String kind) {
        registry.counter("oms.saga.alerts", "kind", kind).increment();
    }

    public void ignoredEvent(String type) {
        registry.counter("oms.saga.events.ignored", "type", type).increment();
    }

    /** Sagas still being worked on (forward or compensating); cached ~2s so a scrape is cheap. */
    double activeSagas() {
        long now = System.currentTimeMillis();
        if (now - activeCachedAt > 2000) {
            activeCache = sagas.countByStateIn(List.of(SagaState.RUNNING, SagaState.COMPENSATING));
            activeCachedAt = now;
        }
        return activeCache;
    }
}
