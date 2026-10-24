package com.enterprise.oms.inventory.messaging;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventPayloads.ReleaseReasons;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.messaging.EventRouter;
import com.enterprise.oms.common.messaging.EventRouterFactory;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Inventory's three inbound streams, all routed through one {@link EventRouter} (envelope parsing, unknown-type
 * ignore, idempotent processing via processed_events in the SAME transaction as the business effect):
 * <ul>
 *   <li>{@code oms.inventory.commands.v1}: ReserveInventory, ReleaseInventory</li>
 *   <li>{@code oms.order.events.v1}: OrderConfirmed (commit reservation), OrderCancelled (release); other order events are ignored</li>
 *   <li>{@code oms.fulfillment.events.v1}: FulfillmentCompleted (ship stock); other fulfillment events are ignored</li>
 * </ul>
 * Malformed payloads throw IllegalArgumentException (non-retryable: straight to the DLT); infrastructure failures
 * propagate and are retried with backoff by the container before dead-lettering.
 */
@Component
public class InventoryEventsListener {

    static final String CONSUMER = "inventory-service";
    private static final Set<String> RELEASE_REASONS =
            Set.of(ReleaseReasons.CANCELLATION, ReleaseReasons.FULFILLMENT_FAILURE, ReleaseReasons.EXPIRED);

    private final EventRouter router;

    public InventoryEventsListener(EventRouterFactory factory, ReservationService reservations,
                                   ReservationLifecycleService lifecycle) {
        this.router = factory.router(CONSUMER)
                .on(EventTypes.RESERVE_INVENTORY, EventPayloads.ReserveInventory.class, (env, p) ->
                        reservations.reserve(required(p.orderId()), p.items(), p.reservationTtlSeconds(),
                                env.correlationId(), env.eventId()))
                .on(EventTypes.RELEASE_INVENTORY, EventPayloads.ReleaseInventory.class, (env, p) ->
                        lifecycle.release(required(p.orderId()), reason(p.reason()), true, env.correlationId(),
                                env.eventId()))
                .on(EventTypes.ORDER_CONFIRMED, EventPayloads.OrderConfirmed.class, (env, p) ->
                        lifecycle.commit(required(p.orderId())))
                .on(EventTypes.ORDER_CANCELLED, EventPayloads.OrderCancelled.class, (env, p) ->
                        lifecycle.release(required(p.orderId()), ReleaseReasons.CANCELLATION, false,
                                env.correlationId(), env.eventId()))
                .on(EventTypes.FULFILLMENT_COMPLETED, EventPayloads.FulfillmentCompleted.class, (env, p) ->
                        lifecycle.ship(required(p.orderId())))
                .build();
    }

    @KafkaListener(id = "inventory-commands", topics = Topics.INVENTORY_COMMANDS,
            containerFactory = "omsKafkaListenerContainerFactory",
            autoStartup = "${oms.inventory.listeners-enabled:true}")
    void onCommand(ConsumerRecord<String, String> record) {
        router.route(record);
    }

    @KafkaListener(id = "inventory-order-events", topics = Topics.ORDER_EVENTS,
            containerFactory = "omsKafkaListenerContainerFactory",
            autoStartup = "${oms.inventory.listeners-enabled:true}")
    void onOrderEvent(ConsumerRecord<String, String> record) {
        router.route(record);
    }

    @KafkaListener(id = "inventory-fulfillment-events", topics = Topics.FULFILLMENT_EVENTS,
            containerFactory = "omsKafkaListenerContainerFactory",
            autoStartup = "${oms.inventory.listeners-enabled:true}")
    void onFulfillmentEvent(ConsumerRecord<String, String> record) {
        router.route(record);
    }

    private static UUID required(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        return orderId;
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ReleaseReasons.CANCELLATION;
        }
        if (!RELEASE_REASONS.contains(reason)) {
            throw new IllegalArgumentException("unknown release reason");
        }
        return reason;
    }
}
