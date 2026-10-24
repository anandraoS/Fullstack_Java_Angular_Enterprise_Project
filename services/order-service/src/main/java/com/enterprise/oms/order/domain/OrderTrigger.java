package com.enterprise.oms.order.domain;

/** What happened to an order (a business fact or a command); the state machine maps (status, trigger) to a decision. */
public enum OrderTrigger {
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    RESERVATION_SUCCEEDED,
    RESERVATION_FAILED,
    CONFIRM,
    FULFILLMENT_COMPLETED,
    FULFILLMENT_FAILED,
    CANCEL_REQUESTED,
    RELEASED_AFTER_CANCELLATION,
    RELEASED_AFTER_FAILURE,
    RESERVATION_EXPIRED,
    SAGA_EXHAUSTED
}
