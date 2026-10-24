package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.inventory.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a reserve request. For a duplicate request ({@code duplicate=true}) this is the ORIGINAL outcome of the
 * order's reservation, rebuilt from the stored row (no stock is touched a second time).
 *
 * @param status  RESERVED/COMMITTED = stock is held; FAILED = refused; RELEASED/EXPIRED = a duplicate arrived after the
 *                reservation was given back
 * @param reason  failure reason code (INSUFFICIENT_STOCK, UNKNOWN_PRODUCT, PRODUCT_INACTIVE) or release reason
 */
public record ReservationOutcome(UUID orderId, UUID reservationId, ReservationStatus status, Instant expiresAt,
                                 List<QuantityItem> items, String reason, List<Shortage> shortages,
                                 boolean duplicate) {

    public boolean holdsStock() {
        return status.holdsStock();
    }

    public boolean failed() {
        return status == ReservationStatus.FAILED;
    }
}
