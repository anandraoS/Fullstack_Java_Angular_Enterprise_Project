package com.enterprise.oms.notification.domain;

import com.enterprise.oms.common.events.EventTypes;

import java.util.Optional;
import java.util.UUID;

/**
 * Template catalogue: which event produces which template and subject. Subjects carry only the short order id
 * (and tracking number), never the recipient address.
 */
public enum NotificationTemplate {
    ORDER_CREATED(EventTypes.ORDER_CREATED, "Order %s received"),
    ORDER_CONFIRMED(EventTypes.ORDER_CONFIRMED, "Order %s confirmed"),
    ORDER_REJECTED(EventTypes.ORDER_REJECTED, "Order %s could not be accepted"),
    ORDER_CANCELLED(EventTypes.ORDER_CANCELLED, "Order %s cancelled"),
    ORDER_FULFILLED(EventTypes.ORDER_FULFILLED, "Order %s delivered - tracking %s"),
    ORDER_FAILED(EventTypes.ORDER_FAILED, "Order %s failed"),
    FULFILLMENT_STARTED(EventTypes.FULFILLMENT_STARTED, "We are preparing order %s"),
    FULFILLMENT_COMPLETED(EventTypes.FULFILLMENT_COMPLETED, "Order %s has shipped - tracking %s"),
    FULFILLMENT_FAILED(EventTypes.FULFILLMENT_FAILED, "Problem preparing order %s");

    private final String eventType;
    private final String subjectFormat;

    NotificationTemplate(String eventType, String subjectFormat) {
        this.eventType = eventType;
        this.subjectFormat = subjectFormat;
    }

    public String eventType() {
        return eventType;
    }

    /** @param trackingNumber used by templates whose subject has a second placeholder; ignored otherwise */
    public String subject(UUID orderId, String trackingNumber) {
        String shortId = orderId == null ? "?" : orderId.toString().substring(0, 8);
        String tracking = trackingNumber == null || trackingNumber.isBlank() ? "pending" : trackingNumber;
        return subjectFormat.contains("%s") && subjectFormat.indexOf("%s") != subjectFormat.lastIndexOf("%s")
                ? subjectFormat.formatted(shortId, tracking) : subjectFormat.formatted(shortId);
    }

    public static Optional<NotificationTemplate> forEventType(String eventType) {
        for (NotificationTemplate t : values()) {
            if (t.eventType.equals(eventType)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
