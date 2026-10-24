package com.enterprise.oms.common.messaging;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.idempotency.IdempotentEventProcessor;
import com.enterprise.oms.common.web.CorrelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Dispatches a raw Kafka record to a typed handler, exactly once per eventId:
 * <ol>
 *   <li>parse envelope (malformed -> {@link com.enterprise.oms.common.events.MalformedEventException}, non-retryable -> DLT);</li>
 *   <li>bind {@code correlationId} to MDC;</li>
 *   <li>unknown eventType -> ignored and counted ({@code oms.events.ignored});</li>
 *   <li>{@link IdempotentEventProcessor#processOnce} wraps the handler in one DB transaction.</li>
 * </ol>
 * Usage:
 * <pre>{@code
 * private final EventRouter router;
 * Listener(EventRouterFactory f, OrderSagaOrchestrator saga) {
 *     this.router = f.router("order-service")
 *         .on(EventTypes.INVENTORY_RESERVED, EventPayloads.InventoryReserved.class, (env, p) -> saga.onReserved(env, p))
 *         .build();
 * }
 * @KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
 * void on(ConsumerRecord<String, String> record) { router.route(record); }
 * }</pre>
 */
public final class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final String consumer;
    private final IdempotentEventProcessor processor;
    private final ObjectMapper mapper;
    private final MessagingMetrics metrics;
    private final Map<String, Consumer<EventEnvelope<JsonNode>>> handlers;

    private EventRouter(Builder b) {
        this.consumer = b.consumer;
        this.processor = b.processor;
        this.mapper = b.mapper;
        this.metrics = b.metrics;
        this.handlers = Map.copyOf(b.handlers);
    }

    public static Builder builder(String consumer, IdempotentEventProcessor processor, ObjectMapper mapper,
                                  MessagingMetrics metrics) {
        return new Builder(consumer, processor, mapper, metrics);
    }

    /** Handles one record; throws on failure so the container's error handler retries / dead-letters. */
    public void route(ConsumerRecord<String, String> record) {
        route(record.topic(), record.value());
    }

    public void route(String topic, String value) {
        EventEnvelope<JsonNode> env = EventJson.parseEnvelope(mapper, value);
        String previous = MDC.get(CorrelationIds.MDC_KEY);
        if (CorrelationIds.isValid(env.correlationId())) {
            MDC.put(CorrelationIds.MDC_KEY, env.correlationId());
        }
        try {
            Consumer<EventEnvelope<JsonNode>> handler = handlers.get(env.eventType());
            if (handler == null) {
                metrics.ignored(topic, env.eventType());
                log.debug("Ignoring unhandled eventType={} eventId={}", env.eventType(), env.eventId());
                return;
            }
            processor.processOnce(consumer, topic, env, () -> handler.accept(env));
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIds.MDC_KEY);
            } else {
                MDC.put(CorrelationIds.MDC_KEY, previous);
            }
        }
    }

    public static final class Builder {
        private final String consumer;
        private final IdempotentEventProcessor processor;
        private final ObjectMapper mapper;
        private final MessagingMetrics metrics;
        private final Map<String, Consumer<EventEnvelope<JsonNode>>> handlers = new HashMap<>();

        private Builder(String consumer, IdempotentEventProcessor processor, ObjectMapper mapper,
                        MessagingMetrics metrics) {
            this.consumer = consumer;
            this.processor = processor;
            this.mapper = mapper;
            this.metrics = metrics;
        }

        /** Typed handler: the payload is converted to {@code type} (failure -> non-retryable). */
        public <P> Builder on(String eventType, Class<P> type, BiConsumer<EventEnvelope<JsonNode>, P> handler) {
            handlers.put(eventType, env -> handler.accept(env, EventJson.payloadAs(mapper, env, type)));
            return this;
        }

        /** Raw handler. */
        public Builder on(String eventType, Consumer<EventEnvelope<JsonNode>> handler) {
            handlers.put(eventType, handler);
            return this;
        }

        public EventRouter build() {
            return new EventRouter(this);
        }
    }
}
