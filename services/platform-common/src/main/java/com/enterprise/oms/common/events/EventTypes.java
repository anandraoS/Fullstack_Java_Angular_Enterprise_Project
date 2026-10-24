package com.enterprise.oms.common.events;

import java.util.Map;
import java.util.Optional;

/** Event/command type names (contract section 7) plus the owning topic and payload class of each. */
public final class EventTypes {

    // order-service -> oms.order.events.v1
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String ORDER_REJECTED = "OrderRejected";
    public static final String ORDER_FULFILLED = "OrderFulfilled";
    public static final String ORDER_FAILED = "OrderFailed";
    // order-service -> oms.inventory.commands.v1
    public static final String RESERVE_INVENTORY = "ReserveInventory";
    public static final String RELEASE_INVENTORY = "ReleaseInventory";
    // inventory-service -> oms.inventory.events.v1
    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_RESERVATION_FAILED = "InventoryReservationFailed";
    public static final String INVENTORY_RELEASED = "InventoryReleased";
    // order-service -> oms.fulfillment.commands.v1
    public static final String START_FULFILLMENT = "StartFulfillment";
    // fulfillment-service -> oms.fulfillment.events.v1
    public static final String FULFILLMENT_STARTED = "FulfillmentStarted";
    public static final String FULFILLMENT_COMPLETED = "FulfillmentCompleted";
    public static final String FULFILLMENT_FAILED = "FulfillmentFailed";

    /** Aggregate types used in the envelope. */
    public static final String AGGREGATE_ORDER = "Order";

    private static final Map<String, String> TOPIC_BY_TYPE = Map.ofEntries(
            Map.entry(ORDER_CREATED, Topics.ORDER_EVENTS),
            Map.entry(ORDER_CONFIRMED, Topics.ORDER_EVENTS),
            Map.entry(ORDER_CANCELLED, Topics.ORDER_EVENTS),
            Map.entry(ORDER_REJECTED, Topics.ORDER_EVENTS),
            Map.entry(ORDER_FULFILLED, Topics.ORDER_EVENTS),
            Map.entry(ORDER_FAILED, Topics.ORDER_EVENTS),
            Map.entry(RESERVE_INVENTORY, Topics.INVENTORY_COMMANDS),
            Map.entry(RELEASE_INVENTORY, Topics.INVENTORY_COMMANDS),
            Map.entry(INVENTORY_RESERVED, Topics.INVENTORY_EVENTS),
            Map.entry(INVENTORY_RESERVATION_FAILED, Topics.INVENTORY_EVENTS),
            Map.entry(INVENTORY_RELEASED, Topics.INVENTORY_EVENTS),
            Map.entry(START_FULFILLMENT, Topics.FULFILLMENT_COMMANDS),
            Map.entry(FULFILLMENT_STARTED, Topics.FULFILLMENT_EVENTS),
            Map.entry(FULFILLMENT_COMPLETED, Topics.FULFILLMENT_EVENTS),
            Map.entry(FULFILLMENT_FAILED, Topics.FULFILLMENT_EVENTS));

    private static final Map<String, Class<?>> PAYLOAD_BY_TYPE = Map.ofEntries(
            Map.entry(ORDER_CREATED, EventPayloads.OrderCreated.class),
            Map.entry(ORDER_CONFIRMED, EventPayloads.OrderConfirmed.class),
            Map.entry(ORDER_CANCELLED, EventPayloads.OrderCancelled.class),
            Map.entry(ORDER_REJECTED, EventPayloads.OrderRejected.class),
            Map.entry(ORDER_FULFILLED, EventPayloads.OrderFulfilled.class),
            Map.entry(ORDER_FAILED, EventPayloads.OrderFailed.class),
            Map.entry(RESERVE_INVENTORY, EventPayloads.ReserveInventory.class),
            Map.entry(RELEASE_INVENTORY, EventPayloads.ReleaseInventory.class),
            Map.entry(INVENTORY_RESERVED, EventPayloads.InventoryReserved.class),
            Map.entry(INVENTORY_RESERVATION_FAILED, EventPayloads.InventoryReservationFailed.class),
            Map.entry(INVENTORY_RELEASED, EventPayloads.InventoryReleased.class),
            Map.entry(START_FULFILLMENT, EventPayloads.StartFulfillment.class),
            Map.entry(FULFILLMENT_STARTED, EventPayloads.FulfillmentStarted.class),
            Map.entry(FULFILLMENT_COMPLETED, EventPayloads.FulfillmentCompleted.class),
            Map.entry(FULFILLMENT_FAILED, EventPayloads.FulfillmentFailed.class));

    private EventTypes() {
    }

    /** Topic an event type is published to; empty for unknown types. */
    public static Optional<String> topicFor(String eventType) {
        return Optional.ofNullable(TOPIC_BY_TYPE.get(eventType));
    }

    /** Payload record class of an event type; empty for unknown types. */
    public static Optional<Class<?>> payloadClassFor(String eventType) {
        return Optional.ofNullable(PAYLOAD_BY_TYPE.get(eventType));
    }

    public static boolean isKnown(String eventType) {
        return TOPIC_BY_TYPE.containsKey(eventType);
    }
}
