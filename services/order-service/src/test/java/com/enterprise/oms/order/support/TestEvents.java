package com.enterprise.oms.order.support;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builders for the events inventory-service and fulfillment-service would send. */
public final class TestEvents {

    public static final String INVENTORY = "inventory-service";
    public static final String FULFILLMENT = "fulfillment-service";

    private TestEvents() {
    }

    public static EventEnvelope<?> reserved(UUID orderId, UUID reservationId, List<EventPayloads.QuantityItem> items) {
        return EventEnvelope.forOrder(EventTypes.INVENTORY_RESERVED, orderId, INVENTORY, "corr-" + orderId, null,
                new EventPayloads.InventoryReserved(orderId, reservationId, items, Instant.now().plusSeconds(900)));
    }

    public static EventEnvelope<?> reservationFailed(UUID orderId, UUID productId, int requested, int available) {
        return EventEnvelope.forOrder(EventTypes.INVENTORY_RESERVATION_FAILED, orderId, INVENTORY, "corr-" + orderId,
                null, new EventPayloads.InventoryReservationFailed(orderId, "INSUFFICIENT_STOCK",
                        List.of(new EventPayloads.Shortage(productId, requested, available))));
    }

    public static EventEnvelope<?> released(UUID orderId, UUID reservationId, String reason) {
        return EventEnvelope.forOrder(EventTypes.INVENTORY_RELEASED, orderId, INVENTORY, "corr-" + orderId, null,
                new EventPayloads.InventoryReleased(orderId, reservationId, reason));
    }

    public static EventEnvelope<?> fulfillmentStarted(UUID orderId) {
        return EventEnvelope.forOrder(EventTypes.FULFILLMENT_STARTED, orderId, FULFILLMENT, "corr-" + orderId, null,
                new EventPayloads.FulfillmentStarted(orderId, UUID.randomUUID()));
    }

    public static EventEnvelope<?> fulfillmentCompleted(UUID orderId, String tracking) {
        return EventEnvelope.forOrder(EventTypes.FULFILLMENT_COMPLETED, orderId, FULFILLMENT, "corr-" + orderId, null,
                new EventPayloads.FulfillmentCompleted(orderId, UUID.randomUUID(), tracking));
    }

    public static EventEnvelope<?> fulfillmentFailed(UUID orderId, String reason) {
        return EventEnvelope.forOrder(EventTypes.FULFILLMENT_FAILED, orderId, FULFILLMENT, "corr-" + orderId, null,
                new EventPayloads.FulfillmentFailed(orderId, UUID.randomUUID(), reason, 3));
    }

    public static String json(EventEnvelope<?> envelope) {
        return EventJson.toJson(envelope);
    }

    /** Same event content with a NEW eventId (a re-emission, not a redelivery). */
    public static EventEnvelope<?> withNewId(EventEnvelope<?> e) {
        return new EventEnvelope<>(UUID.randomUUID(), e.eventType(), e.schemaVersion(), Instant.now(),
                e.correlationId(), e.causationId(), e.aggregateType(), e.aggregateId(), e.producer(), e.payload());
    }
}
