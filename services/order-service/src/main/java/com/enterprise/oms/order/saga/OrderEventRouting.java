package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.messaging.EventRouter;
import com.enterprise.oms.common.messaging.EventRouterFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Maps inventory/fulfillment event types to orchestrator methods. All the machinery (envelope parsing, correlation
 * id, unknown-type tolerance, effectively-once via {@code processed_events} in the SAME transaction as the business
 * effect) comes from platform-common's {@link EventRouter}. Kept separate from the Kafka listener so the exact same
 * routing (including idempotency) can be driven without a broker (BDD tests) or by the listener (production).
 */
@Component
public class OrderEventRouting {

    public static final String CONSUMER = "order-service";

    private final EventRouter router;

    public OrderEventRouting(EventRouterFactory factory, OrderSagaOrchestrator saga) {
        this.router = factory.router(CONSUMER)
                .on(EventTypes.INVENTORY_RESERVED, EventPayloads.InventoryReserved.class,
                        (env, p) -> saga.onInventoryReserved(env.eventId(), p))
                .on(EventTypes.INVENTORY_RESERVATION_FAILED, EventPayloads.InventoryReservationFailed.class,
                        (env, p) -> saga.onInventoryReservationFailed(env.eventId(), p))
                .on(EventTypes.INVENTORY_RELEASED, EventPayloads.InventoryReleased.class,
                        (env, p) -> saga.onInventoryReleased(env.eventId(), p))
                .on(EventTypes.FULFILLMENT_STARTED, EventPayloads.FulfillmentStarted.class,
                        (env, p) -> saga.onFulfillmentStarted(env.eventId(), p))
                .on(EventTypes.FULFILLMENT_COMPLETED, EventPayloads.FulfillmentCompleted.class,
                        (env, p) -> saga.onFulfillmentCompleted(env.eventId(), p))
                .on(EventTypes.FULFILLMENT_FAILED, EventPayloads.FulfillmentFailed.class,
                        (env, p) -> saga.onFulfillmentFailed(env.eventId(), p))
                .build();
    }

    /** Throws on failure (so the container retries / dead-letters). */
    public void route(ConsumerRecord<String, String> record) {
        router.route(record);
    }

    public void route(String topic, String json) {
        router.route(topic, json);
    }
}
