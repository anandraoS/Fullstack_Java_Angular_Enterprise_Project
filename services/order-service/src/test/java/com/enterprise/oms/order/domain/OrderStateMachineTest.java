package com.enterprise.oms.order.domain;

import com.enterprise.oms.common.error.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.enterprise.oms.order.domain.OrderStatus.*;
import static com.enterprise.oms.order.domain.OrderTrigger.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive tests for contract section 6. The expectations below are written from the contract table, NOT derived
 * from the implementation: every one of the 9x9 status pairs and every one of the 9x12 (status, trigger) pairs is
 * asserted.
 */
class OrderStateMachineTest {

    // ---- the contract, transcribed -----------------------------------------------------------------------------

    /** Every valid (from, trigger) -> to of contract section 6. */
    private static final List<Object[]> VALID = List.of(
            new Object[]{CREATED, VALIDATION_PASSED, VALIDATED},
            new Object[]{CREATED, VALIDATION_FAILED, REJECTED},
            new Object[]{VALIDATED, RESERVATION_SUCCEEDED, INVENTORY_RESERVED},
            new Object[]{VALIDATED, RESERVATION_FAILED, REJECTED},
            new Object[]{INVENTORY_RESERVED, CONFIRM, CONFIRMED},
            new Object[]{CONFIRMED, FULFILLMENT_COMPLETED, FULFILLED},
            new Object[]{CONFIRMED, FULFILLMENT_FAILED, COMPENSATION_PENDING},
            new Object[]{CREATED, CANCEL_REQUESTED, CANCELLED},
            new Object[]{VALIDATED, CANCEL_REQUESTED, CANCELLED},
            new Object[]{INVENTORY_RESERVED, CANCEL_REQUESTED, COMPENSATION_PENDING},
            new Object[]{CONFIRMED, CANCEL_REQUESTED, COMPENSATION_PENDING},
            new Object[]{COMPENSATION_PENDING, RELEASED_AFTER_CANCELLATION, CANCELLED},
            new Object[]{COMPENSATION_PENDING, RELEASED_AFTER_FAILURE, FAILED},
            new Object[]{VALIDATED, RESERVATION_EXPIRED, FAILED},
            new Object[]{INVENTORY_RESERVED, RESERVATION_EXPIRED, FAILED},
            new Object[]{CREATED, SAGA_EXHAUSTED, FAILED},
            new Object[]{VALIDATED, SAGA_EXHAUSTED, FAILED},
            new Object[]{INVENTORY_RESERVED, SAGA_EXHAUSTED, FAILED},
            new Object[]{CONFIRMED, SAGA_EXHAUSTED, FAILED},
            new Object[]{COMPENSATION_PENDING, SAGA_EXHAUSTED, FAILED});

    /** (from, trigger) pairs where the trigger's effect is already in place: replays/duplicates are no-ops. */
    private static final List<Object[]> NO_OP = List.of(
            new Object[]{VALIDATED, VALIDATION_PASSED}, new Object[]{INVENTORY_RESERVED, VALIDATION_PASSED},
            new Object[]{CONFIRMED, VALIDATION_PASSED}, new Object[]{FULFILLED, VALIDATION_PASSED},
            new Object[]{COMPENSATION_PENDING, VALIDATION_PASSED},
            new Object[]{REJECTED, VALIDATION_FAILED},
            new Object[]{INVENTORY_RESERVED, RESERVATION_SUCCEEDED}, new Object[]{CONFIRMED, RESERVATION_SUCCEEDED},
            new Object[]{FULFILLED, RESERVATION_SUCCEEDED}, new Object[]{COMPENSATION_PENDING, RESERVATION_SUCCEEDED},
            new Object[]{REJECTED, RESERVATION_FAILED},
            new Object[]{CONFIRMED, CONFIRM}, new Object[]{FULFILLED, CONFIRM},
            new Object[]{COMPENSATION_PENDING, CONFIRM},
            new Object[]{FULFILLED, FULFILLMENT_COMPLETED},
            new Object[]{COMPENSATION_PENDING, FULFILLMENT_FAILED}, new Object[]{FAILED, FULFILLMENT_FAILED},
            new Object[]{CANCELLED, RELEASED_AFTER_CANCELLATION},
            new Object[]{FAILED, RELEASED_AFTER_FAILURE},
            new Object[]{FAILED, RESERVATION_EXPIRED},
            new Object[]{FAILED, SAGA_EXHAUSTED});

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_PAIRS = new EnumMap<>(OrderStatus.class);

    static {
        for (OrderStatus s : OrderStatus.values()) {
            ALLOWED_PAIRS.put(s, EnumSet.noneOf(OrderStatus.class));
        }
        ALLOWED_PAIRS.get(CREATED).addAll(Set.of(VALIDATED, REJECTED, CANCELLED, FAILED));
        ALLOWED_PAIRS.get(VALIDATED).addAll(Set.of(INVENTORY_RESERVED, REJECTED, CANCELLED, FAILED));
        ALLOWED_PAIRS.get(INVENTORY_RESERVED).addAll(Set.of(CONFIRMED, COMPENSATION_PENDING, FAILED));
        ALLOWED_PAIRS.get(CONFIRMED).addAll(Set.of(FULFILLED, COMPENSATION_PENDING, FAILED));
        ALLOWED_PAIRS.get(COMPENSATION_PENDING).addAll(Set.of(CANCELLED, FAILED));
    }

    private static Stream<Arguments> everyStatusPair() {
        List<Arguments> all = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                all.add(Arguments.of(from, to, ALLOWED_PAIRS.get(from).contains(to)));
            }
        }
        return all.stream();
    }

    private static Stream<Arguments> everyStatusAndTrigger() {
        List<Arguments> all = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderTrigger trigger : OrderTrigger.values()) {
                OrderStateMachine.Kind kind = OrderStateMachine.Kind.INVALID;
                OrderStatus to = null;
                for (Object[] v : VALID) {
                    if (v[0] == from && v[1] == trigger) {
                        kind = OrderStateMachine.Kind.TRANSITION;
                        to = (OrderStatus) v[2];
                    }
                }
                for (Object[] n : NO_OP) {
                    if (n[0] == from && n[1] == trigger) {
                        kind = OrderStateMachine.Kind.NO_OP;
                        to = from;
                    }
                }
                all.add(Arguments.of(from, trigger, kind, to));
            }
        }
        return all.stream();
    }

    // ---- behaviours ----------------------------------------------------------------------------------------------

    @Test
    void createdMovesToValidatedWhenValidationPasses() {
        OrderStateMachine.Decision d = OrderStateMachine.decide(CREATED, VALIDATION_PASSED);

        assertThat(d.kind()).isEqualTo(OrderStateMachine.Kind.TRANSITION);
        assertThat(d.to()).isEqualTo(VALIDATED);
    }

    @Test
    void cancelAfterReservationNeedsCompensation() {
        OrderStateMachine.Decision d = OrderStateMachine.decide(INVENTORY_RESERVED, CANCEL_REQUESTED);

        assertThat(d.kind()).isEqualTo(OrderStateMachine.Kind.TRANSITION);
        assertThat(d.to()).isEqualTo(COMPENSATION_PENDING);
    }

    @Test
    void cancelBeforeReservationIsImmediate() {
        assertThat(OrderStateMachine.decide(CREATED, CANCEL_REQUESTED).to()).isEqualTo(CANCELLED);
        assertThat(OrderStateMachine.decide(VALIDATED, CANCEL_REQUESTED).to()).isEqualTo(CANCELLED);
    }

    @Test
    void fulfilledOrderCannotBeCancelled() {
        assertThat(OrderStateMachine.decide(FULFILLED, CANCEL_REQUESTED).kind())
                .isEqualTo(OrderStateMachine.Kind.INVALID);
    }

    @Test
    void replayedValidationOutcomeIsANoOp() {
        assertThat(OrderStateMachine.decide(VALIDATED, VALIDATION_PASSED).kind())
                .isEqualTo(OrderStateMachine.Kind.NO_OP);
    }

    // ---- exhaustive matrices ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("everyStatusPair")
    @DisplayName("every one of the 81 status pairs matches the contract table")
    void statusPairMatrix(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(OrderStateMachine.isAllowed(from, to)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0} + {1} = {2} {3}")
    @MethodSource("everyStatusAndTrigger")
    @DisplayName("every one of the 108 (status, trigger) pairs decides as the contract says")
    void statusTriggerMatrix(OrderStatus from, OrderTrigger trigger, OrderStateMachine.Kind kind, OrderStatus to) {
        OrderStateMachine.Decision d = OrderStateMachine.decide(from, trigger);

        assertThat(d.kind()).isEqualTo(kind);
        assertThat(d.to()).isEqualTo(to);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"FULFILLED", "REJECTED", "CANCELLED", "FAILED"})
    void terminalStatesNeverMove(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(OrderStateMachine.allowedTargets(terminal)).isEmpty();
        for (OrderTrigger t : OrderTrigger.values()) {
            assertThat(OrderStateMachine.decide(terminal, t).kind())
                    .as("%s + %s", terminal, t).isNotEqualTo(OrderStateMachine.Kind.TRANSITION);
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "VALIDATED", "INVENTORY_RESERVED", "CONFIRMED",
            "COMPENSATION_PENDING"})
    void everyNonTerminalStateCanFailWhenTheSagaIsExhausted(OrderStatus from) {
        assertThat(from.isTerminal()).isFalse();
        assertThat(OrderStateMachine.decide(from, SAGA_EXHAUSTED).to()).isEqualTo(FAILED);
    }

    @Test
    void everyAllowedPairIsReachableThroughSomeTrigger() {
        for (OrderStatus from : OrderStatus.values()) {
            Set<OrderStatus> reachable = EnumSet.noneOf(OrderStatus.class);
            for (OrderTrigger t : OrderTrigger.values()) {
                OrderStateMachine.Decision d = OrderStateMachine.decide(from, t);
                if (d.kind() == OrderStateMachine.Kind.TRANSITION) {
                    reachable.add(d.to());
                }
            }
            assertThat(reachable).as("targets of %s", from).isEqualTo(ALLOWED_PAIRS.get(from));
        }
    }

    @Test
    void requireThrowsConflictForInvalidTransitionsAndReturnsTargetOtherwise() {
        assertThat(OrderStateMachine.require(CONFIRMED, CANCEL_REQUESTED)).isEqualTo(COMPENSATION_PENDING);
        assertThatThrownBy(() -> OrderStateMachine.require(FULFILLED, CANCEL_REQUESTED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FULFILLED");
        assertThatThrownBy(() -> OrderStateMachine.require(CANCELLED, CANCEL_REQUESTED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void requireOnAnAlreadyAppliedTriggerKeepsTheStatus() {
        assertThat(OrderStateMachine.require(CONFIRMED, CONFIRM)).isEqualTo(CONFIRMED);
    }

    @Test
    void cancellableStatesAreExactlyThoseOfTheContract() {
        Set<OrderStatus> cancellable = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            if (OrderStateMachine.decide(s, CANCEL_REQUESTED).kind() == OrderStateMachine.Kind.TRANSITION) {
                cancellable.add(s);
            }
        }
        assertThat(cancellable).containsExactlyInAnyOrder(CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED);
    }
}
