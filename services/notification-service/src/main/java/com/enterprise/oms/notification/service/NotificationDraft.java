package com.enterprise.oms.notification.service;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.notification.domain.NotificationTemplate;

import java.util.UUID;

/**
 * Pure mapping result of one event: which template/subject to use and what the event itself says about the
 * customer ({@code customerId}/{@code customerEmail} are null for fulfillment events, which only carry the orderId).
 */
public record NotificationDraft(UUID orderId, UUID customerId, String customerEmail, NotificationTemplate template,
                                String subject) {

    // ---- order events (contain customer identity) -------------------------------------------------------------

    public static NotificationDraft of(EventPayloads.OrderCreated p) {
        return new NotificationDraft(p.orderId(), p.customerId(), p.customerEmail(), NotificationTemplate.ORDER_CREATED,
                NotificationTemplate.ORDER_CREATED.subject(p.orderId(), null));
    }

    public static NotificationDraft of(EventPayloads.OrderConfirmed p) {
        return new NotificationDraft(p.orderId(), p.customerId(), p.customerEmail(),
                NotificationTemplate.ORDER_CONFIRMED, NotificationTemplate.ORDER_CONFIRMED.subject(p.orderId(), null));
    }

    public static NotificationDraft of(EventPayloads.OrderRejected p) {
        return new NotificationDraft(p.orderId(), p.customerId(), p.customerEmail(),
                NotificationTemplate.ORDER_REJECTED, NotificationTemplate.ORDER_REJECTED.subject(p.orderId(), null));
    }

    public static NotificationDraft of(EventPayloads.OrderCancelled p) {
        return new NotificationDraft(p.orderId(), p.customerId(), p.customerEmail(),
                NotificationTemplate.ORDER_CANCELLED, NotificationTemplate.ORDER_CANCELLED.subject(p.orderId(), null));
    }

    public static NotificationDraft of(EventPayloads.OrderFulfilled p) {
        return new NotificationDraft(p.orderId(), p.customerId(), p.customerEmail(),
                NotificationTemplate.ORDER_FULFILLED,
                NotificationTemplate.ORDER_FULFILLED.subject(p.orderId(), p.trackingNumber()));
    }

    public static NotificationDraft of(EventPayloads.OrderFailed p) {
        return new NotificationDraft(p.orderId(), p.customerId(), p.customerEmail(), NotificationTemplate.ORDER_FAILED,
                NotificationTemplate.ORDER_FAILED.subject(p.orderId(), null));
    }

    // ---- fulfillment events (orderId only; contact is resolved from order_contacts) ---------------------------

    public static NotificationDraft of(EventPayloads.FulfillmentStarted p) {
        return new NotificationDraft(p.orderId(), null, null, NotificationTemplate.FULFILLMENT_STARTED,
                NotificationTemplate.FULFILLMENT_STARTED.subject(p.orderId(), null));
    }

    public static NotificationDraft of(EventPayloads.FulfillmentCompleted p) {
        return new NotificationDraft(p.orderId(), null, null, NotificationTemplate.FULFILLMENT_COMPLETED,
                NotificationTemplate.FULFILLMENT_COMPLETED.subject(p.orderId(), p.trackingNumber()));
    }

    public static NotificationDraft of(EventPayloads.FulfillmentFailed p) {
        return new NotificationDraft(p.orderId(), null, null, NotificationTemplate.FULFILLMENT_FAILED,
                NotificationTemplate.FULFILLMENT_FAILED.subject(p.orderId(), null));
    }
}
