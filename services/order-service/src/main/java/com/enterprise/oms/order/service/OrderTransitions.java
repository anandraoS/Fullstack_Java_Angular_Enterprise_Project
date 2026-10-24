package com.enterprise.oms.order.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.order.domain.OrderStateMachine;
import com.enterprise.oms.order.domain.OrderStateMachine.Decision;
import com.enterprise.oms.order.domain.OrderStateMachine.Kind;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.OrderTrigger;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.OrderStatusHistory;
import com.enterprise.oms.order.repository.OrderStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only place that mutates {@code CustomerOrder.status}. It asks the pure {@link OrderStateMachine} what to do and,
 * for a real transition, writes the new status, the {@code order_status_history} row and the audit event in the
 * caller's transaction (MANDATORY: never half-applied), and records the processing timer for terminal statuses.
 */
@Service
public class OrderTransitions {

    private static final Logger log = LoggerFactory.getLogger(OrderTransitions.class);

    private final OrderStatusHistoryRepository history;
    private final AuditService audit;
    private final OrderMetrics metrics;

    public OrderTransitions(OrderStatusHistoryRepository history, AuditService audit, OrderMetrics metrics) {
        this.history = history;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * Event / job driven trigger: duplicates (NO_OP) and impossible triggers (INVALID, e.g. a late event for an order
     * that was cancelled) are tolerated: nothing changes, INVALID is audited and counted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision apply(CustomerOrder order, OrderTrigger trigger, String reason, String actor) {
        Decision d = OrderStateMachine.decide(order.getStatus(), trigger);
        switch (d.kind()) {
            case TRANSITION -> record(order, d.to(), trigger, reason, actor);
            case NO_OP -> log.debug("Order {} already past {} (status {}): no-op", order.getId(), trigger,
                    order.getStatus());
            case INVALID -> {
                log.info("Order {} ignores {} in status {}", order.getId(), trigger, order.getStatus());
                metrics.ignoredEvent(trigger.name());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("trigger", trigger.name());
                detail.put("status", order.getStatus().name());
                audit.record(actor, "Order", order.getId(), "TRANSITION_IGNORED", detail);
            }
        }
        return d;
    }

    /** User command: an invalid trigger is a 409 INVALID_STATE_TRANSITION. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision applyStrict(CustomerOrder order, OrderTrigger trigger, String reason, String actor) {
        Decision d = OrderStateMachine.decide(order.getStatus(), trigger);
        if (d.kind() == Kind.INVALID) {
            throw new InvalidStateTransitionException("Order", order.getStatus(),
                    trigger == OrderTrigger.CANCEL_REQUESTED ? OrderStatus.CANCELLED : trigger);
        }
        if (d.kind() == Kind.TRANSITION) {
            record(order, d.to(), trigger, reason, actor);
        }
        return d;
    }

    private void record(CustomerOrder order, OrderStatus to, OrderTrigger trigger, String reason, String actor) {
        OrderStatus from = order.getStatus();
        Instant now = Instant.now();
        order.changeStatus(to, reason, now);
        history.save(new OrderStatusHistory(order.getId(), from, to, reason, actor, now));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from.name());
        detail.put("to", to.name());
        detail.put("trigger", trigger.name());
        if (reason != null) {
            detail.put("reason", reason);
        }
        audit.record(actor, "Order", order.getId(), "STATUS_" + to.name(), detail);
        if (to.isTerminal()) {
            metrics.orderFinished(to, Duration.between(order.getCreatedAt(), now));
        }
        // lifecycle trail: the MDC (correlationId) of the request / Kafka record / saga thread is on the line
        log.info("Order {} {} -> {} (trigger {}, actor {})", order.getId(), from, to, trigger, actor);
    }

    /** First history row of a new order (null -> CREATED). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreation(CustomerOrder order, String actor) {
        Instant now = Instant.now();
        history.save(new OrderStatusHistory(order.getId(), null, OrderStatus.CREATED, "Order created", actor, now));
        audit.record(actor, "Order", order.getId(), "ORDER_CREATED", Map.of("customerId", order.getCustomerId().toString(),
                "items", order.getItems().size()));
        log.info("Order {} created with {} line(s) (actor {})", order.getId(), order.getItems().size(), actor);
    }
}
