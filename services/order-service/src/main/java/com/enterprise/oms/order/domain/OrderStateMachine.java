package com.enterprise.oms.order.domain;

import com.enterprise.oms.common.error.InvalidStateTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.enterprise.oms.order.domain.OrderStatus.*;

/**
 * The order lifecycle of contract section 6 as a pure, table-driven function: {@code (status, trigger) -> decision}.
 * No Spring, no I/O, no clock, so every pair can be tested exhaustively.
 *
 * <ul>
 *   <li>{@link Kind#TRANSITION}: the trigger is valid in that status; {@code to} is the new status.</li>
 *   <li>{@link Kind#NO_OP}: the trigger's effect is already in place (duplicate delivery / replay of an old event
 *       after the order advanced); nothing must be done, nothing is an error ("transitions are idempotent").</li>
 *   <li>{@link Kind#INVALID}: not allowed. For user commands this becomes 409 INVALID_STATE_TRANSITION
 *       ({@link #require}); for asynchronous events the orchestrator ignores it (and audits it).</li>
 * </ul>
 */
public final class OrderStateMachine {

    public enum Kind { TRANSITION, NO_OP, INVALID }

    /** {@code to} is null for INVALID, the unchanged status for NO_OP. */
    public record Decision(Kind kind, OrderStatus to) {
        public boolean isTransition() {
            return kind == Kind.TRANSITION;
        }
    }

    /** trigger -> (from -> to). */
    private static final Map<OrderTrigger, Map<OrderStatus, OrderStatus>> TRANSITIONS = new EnumMap<>(OrderTrigger.class);
    /** trigger -> statuses in which the trigger has already been applied (or superseded). */
    private static final Map<OrderTrigger, Set<OrderStatus>> ALREADY_APPLIED = new EnumMap<>(OrderTrigger.class);
    /** from -> every status reachable by some trigger (derived, used for the pair matrix). */
    private static final Map<OrderStatus, Set<OrderStatus>> TARGETS = new EnumMap<>(OrderStatus.class);

    static {
        on(OrderTrigger.VALIDATION_PASSED).move(CREATED, VALIDATED)
                .alreadyApplied(VALIDATED, INVENTORY_RESERVED, CONFIRMED, FULFILLED, COMPENSATION_PENDING);
        on(OrderTrigger.VALIDATION_FAILED).move(CREATED, REJECTED)
                .alreadyApplied(REJECTED);
        on(OrderTrigger.RESERVATION_SUCCEEDED).move(VALIDATED, INVENTORY_RESERVED)
                .alreadyApplied(INVENTORY_RESERVED, CONFIRMED, FULFILLED, COMPENSATION_PENDING);
        on(OrderTrigger.RESERVATION_FAILED).move(VALIDATED, REJECTED)
                .alreadyApplied(REJECTED);
        on(OrderTrigger.CONFIRM).move(INVENTORY_RESERVED, CONFIRMED)
                .alreadyApplied(CONFIRMED, FULFILLED, COMPENSATION_PENDING);
        on(OrderTrigger.FULFILLMENT_COMPLETED).move(CONFIRMED, FULFILLED)
                .alreadyApplied(FULFILLED);
        on(OrderTrigger.FULFILLMENT_FAILED).move(CONFIRMED, COMPENSATION_PENDING)
                .alreadyApplied(COMPENSATION_PENDING, FAILED);
        // cancel: a command, never "already applied" (cancelling twice is a 409)
        on(OrderTrigger.CANCEL_REQUESTED)
                .move(CREATED, CANCELLED).move(VALIDATED, CANCELLED)
                .move(INVENTORY_RESERVED, COMPENSATION_PENDING).move(CONFIRMED, COMPENSATION_PENDING);
        on(OrderTrigger.RELEASED_AFTER_CANCELLATION).move(COMPENSATION_PENDING, CANCELLED)
                .alreadyApplied(CANCELLED);
        on(OrderTrigger.RELEASED_AFTER_FAILURE).move(COMPENSATION_PENDING, FAILED)
                .alreadyApplied(FAILED);
        on(OrderTrigger.RESERVATION_EXPIRED).move(VALIDATED, FAILED).move(INVENTORY_RESERVED, FAILED)
                .alreadyApplied(FAILED);
        // any non-terminal -> FAILED
        Rule exhausted = on(OrderTrigger.SAGA_EXHAUSTED).alreadyApplied(FAILED);
        for (OrderStatus s : OrderStatus.values()) {
            if (!s.isTerminal()) {
                exhausted.move(s, FAILED);
            }
        }

        for (OrderStatus s : OrderStatus.values()) {
            TARGETS.put(s, EnumSet.noneOf(OrderStatus.class));
        }
        TRANSITIONS.values().forEach(m -> m.forEach((from, to) -> TARGETS.get(from).add(to)));
    }

    private OrderStateMachine() {
    }

    // ---- API ---------------------------------------------------------------------------------------------------

    public static Decision decide(OrderStatus from, OrderTrigger trigger) {
        OrderStatus to = TRANSITIONS.get(trigger).get(from);
        if (to != null) {
            return new Decision(Kind.TRANSITION, to);
        }
        if (ALREADY_APPLIED.get(trigger).contains(from)) {
            return new Decision(Kind.NO_OP, from);
        }
        return new Decision(Kind.INVALID, null);
    }

    /**
     * Strict variant for user commands.
     *
     * @return the new status (or {@code from} for an already-applied trigger)
     * @throws InvalidStateTransitionException (409) when the trigger is not valid in {@code from}
     */
    public static OrderStatus require(OrderStatus from, OrderTrigger trigger) {
        Decision d = decide(from, trigger);
        if (d.kind() == Kind.INVALID) {
            throw new InvalidStateTransitionException("Order", from,
                    trigger == OrderTrigger.CANCEL_REQUESTED ? CANCELLED : trigger);
        }
        return d.to();
    }

    /** True if some trigger moves {@code from} to {@code to}. */
    public static boolean isAllowed(OrderStatus from, OrderStatus to) {
        return TARGETS.get(from).contains(to);
    }

    public static Set<OrderStatus> allowedTargets(OrderStatus from) {
        return Collections.unmodifiableSet(TARGETS.get(from));
    }

    // ---- table builder -----------------------------------------------------------------------------------------

    private static Rule on(OrderTrigger trigger) {
        TRANSITIONS.put(trigger, new EnumMap<>(OrderStatus.class));
        ALREADY_APPLIED.put(trigger, EnumSet.noneOf(OrderStatus.class));
        return new Rule(trigger);
    }

    private static final class Rule {
        private final OrderTrigger trigger;

        private Rule(OrderTrigger trigger) {
            this.trigger = trigger;
        }

        Rule move(OrderStatus from, OrderStatus to) {
            TRANSITIONS.get(trigger).put(from, to);
            return this;
        }

        Rule alreadyApplied(OrderStatus... statuses) {
            ALREADY_APPLIED.get(trigger).addAll(Set.of(statuses));
            return this;
        }
    }
}
