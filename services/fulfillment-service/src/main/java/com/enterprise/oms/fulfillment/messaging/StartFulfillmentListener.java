package com.enterprise.oms.fulfillment.messaging;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.messaging.EventRouter;
import com.enterprise.oms.common.messaging.EventRouterFactory;
import com.enterprise.oms.fulfillment.service.FulfillmentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code oms.fulfillment.commands.v1}. The router parses the envelope, records
 * {@code processed_events(fulfillment-service, eventId)} in the same transaction as the business effect and
 * dead-letters malformed / invalid commands (non-retryable) after the container's backoff for the rest.
 */
@Component
public class StartFulfillmentListener {

    public static final String CONSUMER = "fulfillment-service";

    private final EventRouter router;

    public StartFulfillmentListener(EventRouterFactory factory, FulfillmentService service) {
        this.router = factory.router(CONSUMER)
                .on(EventTypes.START_FULFILLMENT, EventPayloads.StartFulfillment.class,
                        (env, payload) -> service.start(env, payload))
                .build();
    }

    @KafkaListener(topics = Topics.FULFILLMENT_COMMANDS, containerFactory = "omsKafkaListenerContainerFactory")
    void onCommand(ConsumerRecord<String, String> record) {
        router.route(record);
    }
}
