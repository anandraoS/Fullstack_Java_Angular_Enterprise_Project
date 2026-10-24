package com.enterprise.oms.fulfillment;

import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.fulfillment.domain.FulfillmentStateMachine;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.Set;

import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.COMPLETED;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.FAILED;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.PACKED;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.PENDING;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.PICKING;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulfillmentStateMachineTest {

    private static final Set<String> VALID = Set.of("PENDING>PICKING", "PENDING>FAILED", "PICKING>PACKED",
            "PICKING>FAILED", "PACKED>SHIPPED", "PACKED>FAILED", "SHIPPED>COMPLETED", "SHIPPED>FAILED",
            "FAILED>PENDING");

    @Test
    void everyOrderedPairIsClassifiedExactlyAsSpecified() {
        for (FulfillmentStatus from : FulfillmentStatus.values()) {
            for (FulfillmentStatus to : FulfillmentStatus.values()) {
                boolean expected = VALID.contains(from + ">" + to);
                assertThat(FulfillmentStateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to).isEqualTo(expected);
                if (expected) {
                    assertThatCode(() -> FulfillmentStateMachine.assertTransition(from, to)).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> FulfillmentStateMachine.assertTransition(from, to))
                            .as("%s -> %s", from, to).isInstanceOf(InvalidStateTransitionException.class);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(FulfillmentStatus.class)
    void nothingTransitionsToItself(FulfillmentStatus s) {
        assertThat(FulfillmentStateMachine.canTransition(s, s)).isFalse();
    }

    @Test
    void completedIsTerminal() {
        for (FulfillmentStatus to : FulfillmentStatus.values()) {
            assertThat(FulfillmentStateMachine.canTransition(COMPLETED, to)).isFalse();
        }
    }

    @Test
    void happyPathSequence() {
        assertThat(FulfillmentStateMachine.nextOnSuccess(PENDING)).contains(PICKING);
        assertThat(FulfillmentStateMachine.nextOnSuccess(PICKING)).contains(PACKED);
        assertThat(FulfillmentStateMachine.nextOnSuccess(PACKED)).contains(SHIPPED);
        assertThat(FulfillmentStateMachine.nextOnSuccess(SHIPPED)).contains(COMPLETED);
        assertThat(FulfillmentStateMachine.nextOnSuccess(COMPLETED)).isEqualTo(Optional.empty());
        assertThat(FulfillmentStateMachine.nextOnSuccess(FAILED)).isEqualTo(Optional.empty());
    }

    @Test
    void inFlightFlags() {
        assertThat(PENDING.isInFlight() && PICKING.isInFlight() && PACKED.isInFlight() && SHIPPED.isInFlight()).isTrue();
        assertThat(COMPLETED.isInFlight() || FAILED.isInFlight()).isFalse();
    }

    @Test
    void invalidTransitionCarriesStatesForTheConflictProblem() {
        assertThatThrownBy(() -> FulfillmentStateMachine.assertTransition(COMPLETED, PENDING))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class, e -> {
                    assertThat(e.from()).isEqualTo("COMPLETED");
                    assertThat(e.to()).isEqualTo("PENDING");
                });
    }
}
