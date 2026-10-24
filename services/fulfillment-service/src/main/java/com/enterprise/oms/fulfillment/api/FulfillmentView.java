package com.enterprise.oms.fulfillment.api;

import com.enterprise.oms.fulfillment.domain.Fulfillment;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contract 5.3 Fulfillment JSON. */
public record FulfillmentView(UUID id, UUID orderId, FulfillmentStatus status, String trackingNumber, int attempts,
                              String failureReason, List<EventView> events, Instant createdAt, Instant updatedAt) {

    public record EventView(FulfillmentStatus status, Instant at) {
    }

    /** Must be called inside a transaction (events are lazy). */
    public static FulfillmentView of(Fulfillment f) {
        return new FulfillmentView(f.getId(), f.getOrderId(), f.getStatus(), f.getTrackingNumber(), f.getAttempts(),
                f.getFailureReason(),
                f.getEvents().stream().map(e -> new EventView(e.getStatus(), e.getOccurredAt())).toList(),
                f.getCreatedAt(), f.getUpdatedAt());
    }
}
