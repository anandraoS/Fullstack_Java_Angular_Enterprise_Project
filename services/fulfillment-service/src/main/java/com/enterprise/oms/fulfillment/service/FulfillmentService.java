package com.enterprise.oms.fulfillment.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.observability.Metrics;
import com.enterprise.oms.common.outbox.OutboxService;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.fulfillment.api.FulfillmentView;
import com.enterprise.oms.fulfillment.config.FulfillmentProperties;
import com.enterprise.oms.fulfillment.domain.Fulfillment;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fulfillment use cases. Every state change, its audit row and its outbox event are written in ONE transaction
 * (transactional outbox, contract 7). The simulated lifecycle is advanced step by step by
 * {@link FulfillmentProgressJob}; all progress lives in the database, so a restart simply resumes.
 */
@Service
public class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);
    static final String PRODUCER = "fulfillment-service";
    static final String ENTITY = "Fulfillment";
    private static final Set<FulfillmentStatus> IN_FLIGHT = EnumSet.of(FulfillmentStatus.PENDING,
            FulfillmentStatus.PICKING, FulfillmentStatus.PACKED, FulfillmentStatus.SHIPPED);

    private final FulfillmentRepository repository;
    private final OutboxService outbox;
    private final AuditService audit;
    private final FailureSimulator simulator;
    private final FulfillmentProperties props;
    private final MeterRegistry registry;
    private final Clock clock;

    public FulfillmentService(FulfillmentRepository repository, OutboxService outbox, AuditService audit,
                              FailureSimulator simulator, FulfillmentProperties props, MeterRegistry registry,
                              Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.audit = audit;
        this.simulator = simulator;
        this.props = props;
        this.registry = registry;
        this.clock = clock;
        Metrics.gauge(registry, "oms.fulfillment.in_flight", this,
                s -> s.repository.countByStatusIn(IN_FLIGHT));
    }

    // ---- command handling -------------------------------------------------------------------------------------

    /**
     * Handles {@code StartFulfillment}. Idempotent per order: a second command for the same orderId (even with a new
     * eventId) is a no-op because {@code order_id} is unique.
     *
     * @return the created fulfillment, or empty if one already existed for the order
     */
    @Transactional
    public Optional<Fulfillment> start(EventEnvelope<?> envelope, EventPayloads.StartFulfillment cmd) {
        if (cmd == null || cmd.orderId() == null) {
            throw new IllegalArgumentException("StartFulfillment.orderId is required");
        }
        if (cmd.shippingAddress() == null || cmd.shippingAddress().city() == null
                || cmd.shippingAddress().city().isBlank()) {
            throw new IllegalArgumentException("StartFulfillment.shippingAddress.city is required");
        }
        if (repository.existsByOrderId(cmd.orderId())) {
            log.info("StartFulfillment for orderId={} ignored: fulfillment already exists", cmd.orderId());
            Metrics.counter(registry, "oms.fulfillment.duplicate_commands").increment();
            audit.record("system", ENTITY, cmd.orderId(), "DUPLICATE_START_IGNORED",
                    Map.of("orderId", cmd.orderId().toString()));
            return Optional.empty();
        }
        Instant now = clock.instant();
        EventPayloads.Address a = cmd.shippingAddress();
        String correlationId = envelope.correlationId() != null ? envelope.correlationId()
                : CorrelationIds.currentOrCreate();
        Fulfillment f = new Fulfillment(UUID.randomUUID(), cmd.orderId(), a.line1(), a.city(), a.postalCode(),
                a.country(), cmd.items() == null ? 0 : cmd.items().size(), correlationId, envelope.eventId(), now);
        f.scheduleNextStep(now.plusMillis(props.getStepDelayMs()));
        repository.save(f);
        outbox.append(event(EventTypes.FULFILLMENT_STARTED, f,
                new EventPayloads.FulfillmentStarted(f.getOrderId(), f.getId())));
        audit.record("system", ENTITY, f.getId(), "CREATED",
                Map.of("orderId", f.getOrderId().toString(), "status", f.getStatus().name()));
        transition("PENDING");
        log.info("Fulfillment {} accepted for order {} (status PENDING)", f.getId(), f.getOrderId());
        return Optional.of(f);
    }

    // ---- simulated lifecycle ----------------------------------------------------------------------------------

    /**
     * Performs the next lifecycle step if the fulfillment is in flight and due. Each call is one transaction.
     *
     * @return true if a step (or a failed attempt) was processed
     */
    @Transactional
    public boolean advance(UUID id) {
        Fulfillment f = repository.findById(id).orElse(null);
        if (f == null || !f.getStatus().isInFlight()) {
            return false;
        }
        Instant now = clock.instant();
        if (f.getNextStepAt() != null && f.getNextStepAt().isAfter(now)) {
            return false;
        }
        Instant next = now.plusMillis(props.getStepDelayMs());
        switch (f.getStatus()) {
            case PENDING -> attemptStart(f, now, next);
            case PICKING -> step(f, FulfillmentStatus.PACKED, now, next);
            case PACKED -> {
                f.setTrackingNumber(TrackingNumbers.forOrder(f.getOrderId()));
                step(f, FulfillmentStatus.SHIPPED, now, next);
            }
            case SHIPPED -> complete(f, now);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void attemptStart(Fulfillment f, Instant now, Instant next) {
        int attempt = f.recordAttempt();
        Optional<String> failure = simulator.check(f.getShipCity());
        if (failure.isEmpty()) {
            f.setFailureReason(null);
            step(f, FulfillmentStatus.PICKING, now, next);
            return;
        }
        Metrics.counter(registry, "oms.fulfillment.attempts.failed").increment();
        f.setFailureReason(failure.get());
        audit.record("system", ENTITY, f.getId(), "ATTEMPT_FAILED",
                Map.of("attempt", attempt, "maxAttempts", props.getMaxAttempts(), "reason", failure.get()));
        if (attempt >= props.getMaxAttempts()) {
            f.transitionTo(FulfillmentStatus.FAILED, now);
            f.clearSchedule();
            outbox.append(event(EventTypes.FULFILLMENT_FAILED, f,
                    new EventPayloads.FulfillmentFailed(f.getOrderId(), f.getId(), failure.get(), attempt)));
            audit.record("system", ENTITY, f.getId(), "FAILED",
                    Map.of("attempts", attempt, "reason", failure.get()));
            transition("FAILED");
            Metrics.counter(registry, "oms.fulfillment.failed").increment();
            log.warn("Fulfillment {} for order {} FAILED after {} attempts", f.getId(), f.getOrderId(), attempt);
        } else {
            f.touch(now);
            f.scheduleNextStep(next);
            log.info("Fulfillment {} attempt {}/{} failed, will retry", f.getId(), attempt, props.getMaxAttempts());
        }
    }

    private void step(Fulfillment f, FulfillmentStatus to, Instant now, Instant next) {
        f.transitionTo(to, now);
        f.scheduleNextStep(next);
        audit.record("system", ENTITY, f.getId(), to.name(), Map.of("orderId", f.getOrderId().toString()));
        transition(to.name());
        logLifecycle(f, "Fulfillment {} of order {} is now {}", f.getId(), f.getOrderId(), to);
    }

    private void complete(Fulfillment f, Instant now) {
        f.transitionTo(FulfillmentStatus.COMPLETED, now);
        f.clearSchedule();
        if (f.getTrackingNumber() == null) {
            f.setTrackingNumber(TrackingNumbers.forOrder(f.getOrderId()));
        }
        outbox.append(event(EventTypes.FULFILLMENT_COMPLETED, f,
                new EventPayloads.FulfillmentCompleted(f.getOrderId(), f.getId(), f.getTrackingNumber())));
        audit.record("system", ENTITY, f.getId(), "COMPLETED",
                Map.of("orderId", f.getOrderId().toString(), "trackingNumber", f.getTrackingNumber()));
        transition("COMPLETED");
        logLifecycle(f, "Fulfillment {} of order {} COMPLETED (tracking {})", f.getId(), f.getOrderId(), f.getTrackingNumber());
    }

    /**
     * The scheduler thread that advances a fulfillment has no request/Kafka MDC, so a plain log line would lose the correlation
     * id. Lifecycle lines therefore carry the fulfillment's stored correlation id (found by the correlation-trace check of the
     * integration run: this service used to log nothing at all on the success path).
     */
    private void logLifecycle(Fulfillment f, String message, Object... args) {
        boolean set = MDC.get(CorrelationIds.MDC_KEY) == null && f.getCorrelationId() != null;
        if (set) {
            MDC.put(CorrelationIds.MDC_KEY, f.getCorrelationId());
        }
        try {
            log.info(message, args);
        } finally {
            if (set) {
                MDC.remove(CorrelationIds.MDC_KEY);
            }
        }
    }

    // ---- admin ------------------------------------------------------------------------------------------------

    /** Operator retry: FAILED -> PENDING with attempts reset (409 INVALID_STATE_TRANSITION otherwise). */
    @Transactional
    public FulfillmentView retry(UUID id) {
        Fulfillment f = repository.findById(id).orElseThrow(() -> new NotFoundException("Fulfillment", id));
        Instant now = clock.instant();
        f.transitionTo(FulfillmentStatus.PENDING, now);
        f.resetAttempts();
        f.setFailureReason(null);
        f.scheduleNextStep(now);
        audit.record(ENTITY, f.getId(), "RETRY_REQUESTED", Map.of("orderId", f.getOrderId().toString()));
        transition("PENDING");
        return FulfillmentView.of(f);
    }

    /**
     * Crash recovery: makes sure every in-flight fulfillment has a schedule so the scheduler resumes it after a
     * restart. (State, attempt counter and step due time are persisted, so nothing else is needed.)
     *
     * @return number of in-flight fulfillments found
     */
    @Transactional
    public int recoverInFlight() {
        List<UUID> ids = repository.findIdsByStatusIn(IN_FLIGHT);
        Instant now = clock.instant();
        for (UUID id : ids) {
            repository.findById(id).ifPresent(f -> {
                if (f.getNextStepAt() == null) {
                    f.scheduleNextStep(now);
                }
                audit.record("system", ENTITY, f.getId(), "RESUMED_AFTER_RESTART",
                        Map.of("status", f.getStatus().name(), "attempts", f.getAttempts()));
            });
        }
        if (!ids.isEmpty()) {
            Metrics.counter(registry, "oms.fulfillment.recovered").increment(ids.size());
            log.info("Crash recovery: {} in-flight fulfillment(s) will resume", ids.size());
        }
        return ids.size();
    }

    // ---- queries ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<FulfillmentView> search(UUID orderId, FulfillmentStatus status, Pageable pageable) {
        Specification<Fulfillment> spec = Specification.where(null);
        if (orderId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("orderId"), orderId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return PageResponse.from(repository.findAll(spec, pageable), FulfillmentView::of);
    }

    @Transactional(readOnly = true)
    public FulfillmentView get(UUID id) {
        return repository.findById(id).map(FulfillmentView::of)
                .orElseThrow(() -> new NotFoundException("Fulfillment", id));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private <T> EventEnvelope<T> event(String type, Fulfillment f, T payload) {
        String correlation = f.getCorrelationId() != null ? f.getCorrelationId() : CorrelationIds.currentOrCreate();
        return EventEnvelope.forOrder(type, f.getOrderId(), PRODUCER, correlation, f.getSourceEventId(), payload);
    }

    private void transition(String status) {
        Metrics.counter(registry, "oms.fulfillment.transitions", "status", status).increment();
    }
}
