package com.enterprise.oms.order.domain;

/** Order lifecycle states (contract section 6). */
public enum OrderStatus {
    CREATED,
    VALIDATED,
    INVENTORY_RESERVED,
    CONFIRMED,
    FULFILLED,
    REJECTED,
    CANCELLED,
    COMPENSATION_PENDING,
    FAILED;

    /** FULFILLED, REJECTED, CANCELLED and FAILED never change again. */
    public boolean isTerminal() {
        return this == FULFILLED || this == REJECTED || this == CANCELLED || this == FAILED;
    }
}
