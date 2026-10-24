package com.enterprise.oms.notification.messaging;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.messaging.EventRouter;
import com.enterprise.oms.common.messaging.EventRouterFactory;
import com.enterprise.oms.notification.service.NotificationDraft;
import com.enterprise.oms.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code oms.order.events.v1} and {@code oms.fulfillment.events.v1}. The shared router parses the envelope,
 * writes {@code processed_events(notification-service, eventId)} in the same transaction as the notification row
 * (effectively-once), and sends malformed / invalid events to the DLT. Event types this service does not know
 * (e.g. future order events) are ignored and counted.
 */
@Component
public class NotificationEventsListener {

    public static final String CONSUMER = "notification-service";

    private final EventRouter router;

    public NotificationEventsListener(EventRouterFactory factory, NotificationService service) {
        this.router = factory.router(CONSUMER)
                .on(EventTypes.ORDER_CREATED, EventPayloads.OrderCreated.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.ORDER_CONFIRMED, EventPayloads.OrderConfirmed.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.ORDER_REJECTED, EventPayloads.OrderRejected.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.ORDER_CANCELLED, EventPayloads.OrderCancelled.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.ORDER_FULFILLED, EventPayloads.OrderFulfilled.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.ORDER_FAILED, EventPayloads.OrderFailed.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.FULFILLMENT_STARTED, EventPayloads.FulfillmentStarted.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.FULFILLMENT_COMPLETED, EventPayloads.FulfillmentCompleted.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .on(EventTypes.FULFILLMENT_FAILED, EventPayloads.FulfillmentFailed.class,
                        (env, p) -> service.ingest(env, NotificationDraft.of(p)))
                .build();
    }

    /** Entry point shared by the Kafka listeners (and usable without a broker, e.g. in BDD tests). */
    public void route(String topic, String json) {
        router.route(topic, json);
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
    void onOrderEvent(ConsumerRecord<String, String> record) {
        router.route(record);
    }

    @KafkaListener(topics = Topics.FULFILLMENT_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
    void onFulfillmentEvent(ConsumerRecord<String, String> record) {
        router.route(record);
    }
}
