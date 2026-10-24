package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventPayloads.ReleaseReasons;
import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.OrderStateMachine.Decision;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.OrderTrigger;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.domain.StepStatus;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.OrderItem;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.SagaStepLog;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.repository.SagaStepLogRepository;
import com.enterprise.oms.order.service.Actors;
import com.enterprise.oms.order.service.OrderMetrics;
import com.enterprise.oms.order.service.OrderTransitions;
import com.enterprise.oms.order.service.RetryingTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestration saga (contract section 8): this class knows the steps and tells the other services what to do
 * through commands; they answer with events which come back through {@code OrderEventsListener}.
 *
 * <pre>
 *   VALIDATE (sync REST) -> RESERVE_INVENTORY (cmd ReserveInventory / evt InventoryReserved|ReservationFailed)
 *     -> CONFIRM_ORDER (local) -> FULFILL (cmd StartFulfillment / evt FulfillmentCompleted|Failed)
 *   compensation: RELEASE_INVENTORY (cmd ReleaseInventory / evt InventoryReleased)
 * </pre>
 *
 * Every handler runs inside ONE database transaction together with the idempotent-consumer marker
 * ({@code processed_events}); state change + history + saga log + audit + outbox rows commit or roll back together.
 * It is NOT a distributed ACID transaction: between steps the services are eventually consistent and compensation is
 * semantic (release the reservation), see README "Failure modes".
 */
@Service
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final OrderRepository orders;
    private final SagaInstanceRepository sagas;
    private final SagaStepLogRepository stepLog;
    private final OrderTransitions transitions;
    private final SagaMessaging messaging;
    private final InventoryClient inventory;
    private final AuditService audit;
    private final OrderMetrics metrics;
    private final SagaProperties props;
    private final RetryingTransactions retryTx;

    public OrderSagaOrchestrator(OrderRepository orders, SagaInstanceRepository sagas, SagaStepLogRepository stepLog,
                                 OrderTransitions transitions, SagaMessaging messaging, InventoryClient inventory,
                                 AuditService audit, OrderMetrics metrics, SagaProperties props,
                                 RetryingTransactions retryTx) {
        this.orders = orders;
        this.sagas = sagas;
        this.stepLog = stepLog;
        this.transitions = transitions;
        this.messaging = messaging;
        this.inventory = inventory;
        this.audit = audit;
        this.metrics = metrics;
        this.props = props;
        this.retryTx = retryTx;
    }

    // =====================================================================================================
    // start + step 1: validation (synchronous REST call, so NOT inside a DB transaction)
    // =====================================================================================================

    /** Creates the saga row of a new order; joins the caller's transaction. */
    @Transactional
    public SagaInstance start(CustomerOrder order) {
        Instant now = Instant.now();
        SagaInstance saga = sagas.save(new SagaInstance(order.getId(), now));
        logStep(saga, SagaStep.VALIDATE, StepStatus.STARTED, 0, "order accepted");
        return saga;
    }

    private record ValidationInput(List<EventPayloads.QuantityItem> items, String correlationId) {
    }

    /**
     * Runs the VALIDATE step: read (tx) -> REST call to inventory (no tx, so no connection is held during I/O) ->
     * apply the outcome (tx). Safe to call repeatedly: it does nothing unless the order is still CREATED.
     */
    public void validate(UUID orderId) {
        ValidationInput input = retryTx.run(() -> orders.findWithItemsById(orderId)
                .filter(o -> o.getStatus() == OrderStatus.CREATED)
                .map(o -> new ValidationInput(o.getItems().stream()
                        .map(i -> new EventPayloads.QuantityItem(i.getProductId(), i.getQuantity())).toList(),
                        o.getCorrelationId()))
                .orElse(null));
        if (input == null) {
            return;
        }
        String previous = MDC.get(CorrelationIds.MDC_KEY);
        if (CorrelationIds.isValid(input.correlationId())) {
            MDC.put(CorrelationIds.MDC_KEY, input.correlationId());
        }
        try {
            InventoryClient.ValidationResult result;
            try {
                result = inventory.validate(input.items());
            } catch (RuntimeException e) {
                log.warn("Validation of order {} failed: {}", orderId, e.toString());
                retryTx.runVoid(() -> onValidationUnavailable(orderId, e));
                return;
            }
            retryTx.runVoid(() -> applyValidation(orderId, result));
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIds.MDC_KEY);
            } else {
                MDC.put(CorrelationIds.MDC_KEY, previous);
            }
        }
    }

    void applyValidation(UUID orderId, InventoryClient.ValidationResult result) {
        CustomerOrder order = orders.findWithItemsById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.CREATED) {
            log.info("Validation result for order {} dropped: status is {}", orderId,
                    order == null ? "unknown" : order.getStatus());
            return;
        }
        SagaInstance saga = sagas.findByOrderId(orderId).orElseThrow(() -> new NotFoundException("Saga", orderId));
        Map<UUID, InventoryClient.Line> byProduct = new HashMap<>();
        result.safeLines().forEach(l -> byProduct.put(l.productId(), l));

        Map<UUID, CustomerOrder.PricedLine> priced = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            InventoryClient.Line line = byProduct.get(item.getProductId());
            if (line == null) {
                problems.add(item.getProductId() + ": not validated by inventory");
            } else if (!line.ok() || !line.active()) {
                problems.add((line.sku() != null ? line.sku() : item.getProductId().toString()) + ": "
                        + (line.reason() != null ? line.reason() : line.active() ? "not available" : "inactive"));
            } else if (line.unitPrice() == null) {
                problems.add(item.getProductId() + ": no price");
            } else {
                priced.put(item.getProductId(), new CustomerOrder.PricedLine(line.sku(), line.name(), line.unitPrice()));
            }
        }
        if (problems.isEmpty() && !result.valid()) {
            problems.add("inventory declared the order invalid");
        }

        if (problems.isEmpty()) {
            order.applyPrices(priced);
            Decision d = transitions.apply(order, OrderTrigger.VALIDATION_PASSED, null, Actors.SAGA);
            if (d.isTransition()) {
                logStep(saga, SagaStep.VALIDATE, StepStatus.SUCCEEDED, saga.getAttempts() + 1,
                        "priced " + priced.size() + " line(s), total " + order.getTotalAmount());
                saga.advanceTo(SagaStep.RESERVE_INVENTORY, Instant.now());
                logStep(saga, SagaStep.RESERVE_INVENTORY, StepStatus.STARTED, 1, "ReserveInventory sent");
                // OrderCreated carries the price snapshot, so it is emitted here (see README / contract note)
                messaging.orderCreated(order, null);
                messaging.reserveInventory(order, null);
            }
        } else {
            String reason = truncate("Validation failed: " + String.join("; ", problems), 900);
            Decision d = transitions.apply(order, OrderTrigger.VALIDATION_FAILED, reason, Actors.SAGA);
            if (d.isTransition()) {
                saga.setState(SagaState.ABORTED, Instant.now());
                logStep(saga, SagaStep.VALIDATE, StepStatus.FAILED, saga.getAttempts() + 1, reason);
                messaging.orderRejected(order, reason, null);
            }
        }
    }

    void onValidationUnavailable(UUID orderId, RuntimeException cause) {
        CustomerOrder order = orders.findWithItemsById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.CREATED) {
            return;
        }
        SagaInstance saga = sagas.findByOrderId(orderId).orElseThrow(() -> new NotFoundException("Saga", orderId));
        Instant now = Instant.now();
        int attempt = saga.incrementAttempts(now);
        String error = truncate(LogMasking.sanitize(cause.getClass().getSimpleName() + ": " + cause.getMessage()), 900);
        saga.recordError(error, now);
        logStep(saga, SagaStep.VALIDATE, StepStatus.FAILED, attempt, error);
        if (attempt >= props.getMaxAttempts()) {
            exhaust(order, saga, "Validation failed after " + attempt + " attempts: " + error);
        }
    }

    // =====================================================================================================
    // events from inventory and fulfillment (called inside processed_events' transaction)
    // =====================================================================================================

    @Transactional
    public void onInventoryReserved(UUID eventId, EventPayloads.InventoryReserved p) {
        Loaded l = load(p.orderId(), "InventoryReserved");
        if (l == null) {
            return;
        }
        CustomerOrder order = l.order();
        SagaInstance saga = l.saga();
        if (saga.getReservationId() == null) {
            saga.setReservationId(p.reservationId());
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            lateReservationAfterCancel(order, saga, eventId);
            return;
        }
        Decision d = transitions.apply(order, OrderTrigger.RESERVATION_SUCCEEDED, null, Actors.SAGA);
        if (!d.isTransition()) {
            return;
        }
        logStep(saga, SagaStep.RESERVE_INVENTORY, StepStatus.SUCCEEDED, saga.getAttempts() + 1,
                "reservation " + p.reservationId());
        confirmAndStartFulfillment(order, saga, eventId);
    }

    /** INVENTORY_RESERVED -> CONFIRMED, then publish OrderConfirmed + StartFulfillment (one transaction). */
    private void confirmAndStartFulfillment(CustomerOrder order, SagaInstance saga, UUID causation) {
        Decision confirm = transitions.apply(order, OrderTrigger.CONFIRM, null, Actors.SAGA);
        if (!confirm.isTransition()) {
            return;
        }
        logStep(saga, SagaStep.CONFIRM_ORDER, StepStatus.SUCCEEDED, 1, "order confirmed");
        saga.advanceTo(SagaStep.FULFILL, Instant.now());
        logStep(saga, SagaStep.FULFILL, StepStatus.STARTED, 1, "StartFulfillment sent");
        messaging.orderConfirmed(order, causation);
        messaging.startFulfillment(order, causation);
    }

    private void lateReservationAfterCancel(CustomerOrder order, SagaInstance saga, UUID eventId) {
        saga.touch(Instant.now());
        logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.STARTED, 1,
                "reservation arrived after the order was cancelled; ReleaseInventory sent");
        messaging.releaseInventory(order, ReleaseReasons.CANCELLATION, eventId);
        metrics.compensation("LATE_RESERVATION");
        audit.record(Actors.SAGA, "Order", order.getId(), "LATE_RESERVATION_RELEASED",
                Map.of("reservationId", String.valueOf(saga.getReservationId())));
    }

    @Transactional
    public void onInventoryReservationFailed(UUID eventId, EventPayloads.InventoryReservationFailed p) {
        Loaded l = load(p.orderId(), "InventoryReservationFailed");
        if (l == null) {
            return;
        }
        String shortages = p.shortages() == null ? "" : p.shortages().stream()
                .map(s -> s.productId() + " requested " + s.requested() + " available " + s.available())
                .collect(Collectors.joining("; "));
        String reason = truncate("Insufficient stock" + (p.reason() != null ? " (" + p.reason() + ")" : "")
                + (shortages.isEmpty() ? "" : ": " + shortages), 900);
        Decision d = transitions.apply(l.order(), OrderTrigger.RESERVATION_FAILED, reason, Actors.SAGA);
        if (d.isTransition()) {
            l.saga().setState(SagaState.ABORTED, Instant.now());
            logStep(l.saga(), SagaStep.RESERVE_INVENTORY, StepStatus.FAILED, l.saga().getAttempts() + 1, reason);
            messaging.orderRejected(l.order(), reason, eventId);
        }
    }

    @Transactional
    public void onInventoryReleased(UUID eventId, EventPayloads.InventoryReleased p) {
        Loaded l = load(p.orderId(), "InventoryReleased");
        if (l == null) {
            return;
        }
        CustomerOrder order = l.order();
        SagaInstance saga = l.saga();
        if (saga.getReservationId() == null) {
            saga.setReservationId(p.reservationId());
        }
        saga.setReservationReleased(true);
        OrderStatus status = order.getStatus();

        if (ReleaseReasons.EXPIRED.equals(p.reason())
                && (status == OrderStatus.VALIDATED || status == OrderStatus.INVENTORY_RESERVED)) {
            String reason = "Inventory reservation expired before the order was confirmed";
            Decision d = transitions.apply(order, OrderTrigger.RESERVATION_EXPIRED, reason, Actors.SAGA);
            if (d.isTransition()) {
                saga.setState(SagaState.FAILED, Instant.now());
                logStep(saga, SagaStep.RESERVE_INVENTORY, StepStatus.FAILED, saga.getAttempts() + 1, reason);
                messaging.orderFailed(order, reason, eventId);
            }
            return;
        }
        if (status == OrderStatus.COMPENSATION_PENDING) {
            boolean cancellation = !ReleaseReasons.FULFILLMENT_FAILURE.equals(saga.getReason());
            String keepReason = order.getStatusReason();
            Decision d = transitions.apply(order,
                    cancellation ? OrderTrigger.RELEASED_AFTER_CANCELLATION : OrderTrigger.RELEASED_AFTER_FAILURE,
                    keepReason, Actors.SAGA);
            if (d.isTransition()) {
                saga.setState(cancellation ? SagaState.COMPENSATED : SagaState.FAILED, Instant.now());
                logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.COMPENSATED, saga.getAttempts() + 1,
                        "inventory released");
                if (cancellation) {
                    messaging.orderCancelled(order, keepReason, eventId);
                } else {
                    messaging.orderFailed(order, keepReason != null ? keepReason
                            : "Fulfillment failed; inventory released", eventId);
                }
            }
            return;
        }
        // e.g. the release confirmation of a late reservation, or a replay: bookkeeping only
        saga.touch(Instant.now());
        logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.SUCCEEDED, 1,
                "release confirmed (order status " + status + ")");
    }

    @Transactional
    public void onFulfillmentStarted(UUID eventId, EventPayloads.FulfillmentStarted p) {
        Loaded l = load(p.orderId(), "FulfillmentStarted");
        if (l == null) {
            return;
        }
        boolean first = !l.saga().isFulfillmentStarted();
        l.saga().setFulfillmentStarted(true);
        l.saga().touch(Instant.now());
        if (first) {
            logStep(l.saga(), SagaStep.FULFILL, StepStatus.STARTED, 1, "fulfillment " + p.fulfillmentId() + " started");
        }
    }

    @Transactional
    public void onFulfillmentCompleted(UUID eventId, EventPayloads.FulfillmentCompleted p) {
        Loaded l = load(p.orderId(), "FulfillmentCompleted");
        if (l == null) {
            return;
        }
        Decision d = transitions.apply(l.order(), OrderTrigger.FULFILLMENT_COMPLETED, null, Actors.SAGA);
        if (d.isTransition()) {
            l.saga().setFulfillmentStarted(true);
            l.saga().setState(SagaState.COMPLETED, Instant.now());
            logStep(l.saga(), SagaStep.FULFILL, StepStatus.SUCCEEDED, l.saga().getAttempts() + 1,
                    "tracking " + p.trackingNumber());
            messaging.orderFulfilled(l.order(), p.trackingNumber(), eventId);
        }
    }

    @Transactional
    public void onFulfillmentFailed(UUID eventId, EventPayloads.FulfillmentFailed p) {
        Loaded l = load(p.orderId(), "FulfillmentFailed");
        if (l == null) {
            return;
        }
        failFulfillment(l.order(), l.saga(),
                truncate("Fulfillment failed: " + p.reason() + " (attempts " + p.attempts() + ")", 900), eventId);
    }

    /** CONFIRMED -> COMPENSATION_PENDING and ReleaseInventory(FULFILLMENT_FAILURE). */
    private void failFulfillment(CustomerOrder order, SagaInstance saga, String reason, UUID causation) {
        Decision d = transitions.apply(order, OrderTrigger.FULFILLMENT_FAILED, reason, Actors.SAGA);
        if (!d.isTransition()) {
            return;
        }
        logStep(saga, SagaStep.FULFILL, StepStatus.FAILED, saga.getAttempts() + 1, reason);
        saga.startCompensation(ReleaseReasons.FULFILLMENT_FAILURE, Instant.now());
        logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.STARTED, 1, "ReleaseInventory sent");
        messaging.releaseInventory(order, ReleaseReasons.FULFILLMENT_FAILURE, causation);
        metrics.compensation(ReleaseReasons.FULFILLMENT_FAILURE);
    }

    // =====================================================================================================
    // cancel (user command)
    // =====================================================================================================

    /**
     * Cancels an order inside the caller's transaction. Before a reservation exists the order is CANCELLED at once;
     * after that it moves to COMPENSATION_PENDING and the reservation is released first (semantic rollback).
     *
     * @throws InvalidStateTransitionException 409 when the status does not allow cancelling
     */
    @Transactional
    public void cancel(CustomerOrder order, SagaInstance saga, String reason) {
        String actor = Actors.current();
        Decision d = transitions.applyStrict(order, OrderTrigger.CANCEL_REQUESTED, reason, actor);
        saga.setReason(ReleaseReasons.CANCELLATION);
        if (d.to() == OrderStatus.CANCELLED) {
            saga.setState(SagaState.ABORTED, Instant.now());
            logStep(saga, saga.getCurrentStep(), StepStatus.SKIPPED, 0, "cancelled before any reservation: " + reason);
            messaging.orderCancelled(order, reason, null);
        } else {
            saga.startCompensation(ReleaseReasons.CANCELLATION, Instant.now());
            logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.STARTED, 1, "cancel requested; ReleaseInventory sent");
            messaging.releaseInventory(order, ReleaseReasons.CANCELLATION, null);
            metrics.compensation(ReleaseReasons.CANCELLATION);
        }
    }

    // =====================================================================================================
    // recovery / admin retry
    // =====================================================================================================

    public enum RedriveResult { SKIPPED, REDRIVEN, EXHAUSTED }

    /**
     * Re-drives the current step of one order. Used by {@link SagaRecoveryJob} (automatic, attempt-limited) and by
     * the admin retry endpoint ({@code manual}: resets attempts and re-opens a flagged saga). Every re-sent command
     * is idempotent at its receiver, so repeating this is always safe.
     */
    public RedriveResult recover(UUID orderId, boolean manual) {
        boolean[] validateAfterCommit = {false};
        RedriveResult result = retryTx.run(() -> {
            CustomerOrder order = orders.findWithItemsById(orderId)
                    .orElseThrow(() -> new NotFoundException("Order", orderId));
            SagaInstance saga = sagas.findByOrderId(orderId)
                    .orElseThrow(() -> new NotFoundException("Saga", orderId));
            if (order.getStatus().isTerminal()) {
                if (manual) {
                    throw new InvalidStateTransitionException("Order", order.getStatus(), "RETRY");
                }
                return RedriveResult.SKIPPED;
            }
            Instant now = Instant.now();
            if (manual) {
                saga.resetAttempts(now);
                if (saga.getState() == SagaState.NEEDS_ATTENTION) {
                    saga.setState(SagaState.COMPENSATING, now);
                }
            } else if (saga.getAttempts() >= props.getMaxAttempts()) {
                exhaust(order, saga, "No progress after " + saga.getAttempts() + " re-drive attempts at step "
                        + saga.getCurrentStep());
                return RedriveResult.EXHAUSTED;
            }
            switch (order.getStatus()) {
                case CREATED -> {
                    validateAfterCommit[0] = true;
                    saga.touch(now);
                }
                case VALIDATED -> {
                    int n = saga.incrementAttempts(now);
                    messaging.reserveInventory(order, null);
                    logStep(saga, SagaStep.RESERVE_INVENTORY, StepStatus.RETRIED, n, "ReserveInventory re-sent");
                }
                case INVENTORY_RESERVED -> {
                    saga.incrementAttempts(now);
                    confirmAndStartFulfillment(order, saga, null);
                }
                case CONFIRMED -> {
                    int n = saga.incrementAttempts(now);
                    messaging.startFulfillment(order, null);
                    logStep(saga, SagaStep.FULFILL, StepStatus.RETRIED, n, "StartFulfillment re-sent");
                }
                case COMPENSATION_PENDING -> {
                    int n = saga.incrementAttempts(now);
                    messaging.releaseInventory(order, saga.getReason() != null ? saga.getReason()
                            : ReleaseReasons.CANCELLATION, null);
                    logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.RETRIED, n, "ReleaseInventory re-sent");
                }
                default -> { }
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", order.getStatus().name());
            detail.put("step", saga.getCurrentStep().name());
            detail.put("attempts", saga.getAttempts());
            detail.put("manual", manual);
            audit.record(manual ? Actors.current() : Actors.SYSTEM, "Order", orderId, "SAGA_REDRIVEN", detail);
            return RedriveResult.REDRIVEN;
        });
        if (validateAfterCommit[0]) {
            validate(orderId);
        }
        return result;
    }

    /**
     * The step ran out of attempts. Non-compensable steps (nothing to undo) fail the order; a CONFIRMED order is
     * treated like a fulfillment failure (compensate); a stuck compensation is flagged for humans.
     */
    void exhaust(CustomerOrder order, SagaInstance saga, String reason) {
        String why = truncate(reason, 900);
        OrderStatus statusBefore = order.getStatus();
        switch (statusBefore) {
            case CREATED, VALIDATED, INVENTORY_RESERVED -> {
                boolean mayHoldReservation = statusBefore != OrderStatus.CREATED;
                Decision d = transitions.apply(order, OrderTrigger.SAGA_EXHAUSTED, why, Actors.SAGA);
                if (d.isTransition()) {
                    saga.recordError(why, Instant.now());
                    saga.setState(SagaState.FAILED, Instant.now());
                    logStep(saga, saga.getCurrentStep(), StepStatus.FAILED, saga.getAttempts(), why);
                    messaging.orderFailed(order, why, null);
                    if (mayHoldReservation) {
                        // the reply to ReserveInventory may have been lost: defensively release (idempotent)
                        messaging.releaseInventory(order, ReleaseReasons.CANCELLATION, null);
                        metrics.compensation("EXHAUSTED");
                    }
                }
            }
            case CONFIRMED -> failFulfillment(order, saga, why, null);
            case COMPENSATION_PENDING -> {
                saga.setState(SagaState.NEEDS_ATTENTION, Instant.now());
                saga.recordError(why, Instant.now());
                logStep(saga, SagaStep.RELEASE_INVENTORY, StepStatus.FAILED, saga.getAttempts(), why);
            }
            default -> { return; }
        }
        metrics.alert("exhausted-" + statusBefore.name().toLowerCase());
        audit.record(Actors.SAGA, "Order", order.getId(), "SAGA_EXHAUSTED", Map.of("reason", why));
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    private record Loaded(CustomerOrder order, SagaInstance saga) {
    }

    /** Loads order + saga for an event; unknown orders (or sagas) are ignored and counted, never an error. */
    private Loaded load(UUID orderId, String eventType) {
        CustomerOrder order = orderId == null ? null : orders.findWithItemsById(orderId).orElse(null);
        SagaInstance saga = order == null ? null : sagas.findByOrderId(orderId).orElse(null);
        if (order == null || saga == null) {
            log.warn("{} for unknown order {} ignored", eventType, orderId);
            metrics.ignoredEvent(eventType + ".unknownOrder");
            return null;
        }
        return new Loaded(order, saga);
    }

    void logStep(SagaInstance saga, SagaStep step, StepStatus status, int attempt, String detail) {
        stepLog.save(new SagaStepLog(saga.getId(), saga.getOrderId(), step, status, attempt, detail, Instant.now()));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
