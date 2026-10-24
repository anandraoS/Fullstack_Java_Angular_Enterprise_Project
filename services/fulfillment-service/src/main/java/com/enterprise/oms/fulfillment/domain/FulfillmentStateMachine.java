package com.enterprise.oms.fulfillment.domain;

import com.enterprise.oms.common.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.COMPLETED;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.FAILED;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.PACKED;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.PENDING;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.PICKING;
import static com.enterprise.oms.fulfillment.domain.FulfillmentStatus.SHIPPED;

/**
 * Pure transition rules. Happy path PENDING -> PICKING -> PACKED -> SHIPPED -> COMPLETED; any in-flight state may
 * FAIL; the only way out of FAILED is an operator retry (FAILED -> PENDING); COMPLETED is terminal.
 */
public final class FulfillmentStateMachine {

    private static final Map<FulfillmentStatus, Set<FulfillmentStatus>> ALLOWED = new EnumMap<>(FulfillmentStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(PICKING, FAILED));
        ALLOWED.put(PICKING, EnumSet.of(PACKED, FAILED));
        ALLOWED.put(PACKED, EnumSet.of(SHIPPED, FAILED));
        ALLOWED.put(SHIPPED, EnumSet.of(COMPLETED, FAILED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(FulfillmentStatus.class));
        ALLOWED.put(FAILED, EnumSet.of(PENDING));
    }

    private FulfillmentStateMachine() {
    }

    public static boolean canTransition(FulfillmentStatus from, FulfillmentStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** @throws InvalidStateTransitionException (409) for any pair that is not allowed */
    public static void assertTransition(FulfillmentStatus from, FulfillmentStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException("Fulfillment", from, to);
        }
    }

    /** Next state on the happy path; empty for COMPLETED and FAILED. */
    public static Optional<FulfillmentStatus> nextOnSuccess(FulfillmentStatus from) {
        return switch (from) {
            case PENDING -> Optional.of(PICKING);
            case PICKING -> Optional.of(PACKED);
            case PACKED -> Optional.of(SHIPPED);
            case SHIPPED -> Optional.of(COMPLETED);
            case COMPLETED, FAILED -> Optional.empty();
        };
    }
}
