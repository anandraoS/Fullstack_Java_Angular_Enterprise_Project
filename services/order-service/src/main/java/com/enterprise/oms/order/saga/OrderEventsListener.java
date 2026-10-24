package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry points for the replies of inventory-service and fulfillment-service (consumer group = service name,
 * AckMode.RECORD, retry with backoff then {@code <topic>.DLT}: all from platform-common's container factory).
 * Disabled with {@code oms.saga.listeners-enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "oms.saga", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventsListener {

    private final OrderEventRouting routing;

    public OrderEventsListener(OrderEventRouting routing) {
        this.routing = routing;
    }

    @KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
    void onInventoryEvent(ConsumerRecord<String, String> record) {
        routing.route(record);
    }

    @KafkaListener(topics = Topics.FULFILLMENT_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
    void onFulfillmentEvent(ConsumerRecord<String, String> record) {
        routing.route(record);
    }
}
