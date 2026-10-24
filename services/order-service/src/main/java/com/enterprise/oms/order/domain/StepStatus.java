package com.enterprise.oms.order.domain;

/** Outcome recorded in saga_step_log. */
public enum StepStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    RETRIED,
    COMPENSATED,
    SKIPPED
}
