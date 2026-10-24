package com.enterprise.oms.inventory.domain;

/** Lifecycle of a reservation (contract section 7). {@code FAILED} = the reserve attempt was refused (no stock held). */
public enum ReservationStatus {
    RESERVED, COMMITTED, RELEASED, EXPIRED, FAILED;

    /** States that hold stock in {@code stock.reserved}. */
    public boolean holdsStock() {
        return this == RESERVED || this == COMMITTED;
    }
}
