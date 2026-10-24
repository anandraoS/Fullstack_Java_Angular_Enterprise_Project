package com.enterprise.oms.fulfillment.domain;

/** Lifecycle states of a fulfillment (contract 5.3). */
public enum FulfillmentStatus {
    PENDING, PICKING, PACKED, SHIPPED, COMPLETED, FAILED;

    /** In flight = still progressed by the scheduler. */
    public boolean isInFlight() {
        return this == PENDING || this == PICKING || this == PACKED || this == SHIPPED;
    }
}
