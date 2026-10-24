package com.enterprise.oms.order.domain;

/** Lifecycle of a saga instance (one per order). */
public enum SagaState {
    /** forward path in progress (VALIDATE, RESERVE_INVENTORY, CONFIRM_ORDER, FULFILL). */
    RUNNING,
    /** compensation (RELEASE_INVENTORY) in progress. */
    COMPENSATING,
    /** order fulfilled. */
    COMPLETED,
    /** ended without any compensation needed (rejected, or cancelled before a reservation existed). */
    ABORTED,
    /** compensation finished (inventory released). */
    COMPENSATED,
    /** failed terminally (non-compensable step exhausted, reservation expired, fulfillment failure after release). */
    FAILED,
    /** compensation retries exhausted: flagged for an operator / reconciliation; admin retry re-opens it. */
    NEEDS_ATTENTION;

    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED || this == COMPENSATED || this == FAILED;
    }

    /** States the SagaRecoveryJob re-drives. */
    public boolean isActive() {
        return this == RUNNING || this == COMPENSATING;
    }
}
