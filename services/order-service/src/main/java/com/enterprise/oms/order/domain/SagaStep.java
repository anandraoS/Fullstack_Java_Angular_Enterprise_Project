package com.enterprise.oms.order.domain;

/** Saga steps: VALIDATE -> RESERVE_INVENTORY -> CONFIRM_ORDER -> FULFILL; compensation: RELEASE_INVENTORY. */
public enum SagaStep {
    VALIDATE,
    RESERVE_INVENTORY,
    CONFIRM_ORDER,
    FULFILL,
    RELEASE_INVENTORY
}
