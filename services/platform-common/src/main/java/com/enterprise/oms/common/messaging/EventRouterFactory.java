package com.enterprise.oms.common.messaging;

import com.enterprise.oms.common.idempotency.IdempotentEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Injectable factory for {@link EventRouter.Builder}. */
public class EventRouterFactory {

    private final IdempotentEventProcessor processor;
    private final ObjectMapper mapper;
    private final MessagingMetrics metrics;

    public EventRouterFactory(IdempotentEventProcessor processor, ObjectMapper mapper, MessagingMetrics metrics) {
        this.processor = processor;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /** @param consumer logical consumer name stored in processed_events (use the service name). */
    public EventRouter.Builder router(String consumer) {
        return EventRouter.builder(consumer, processor, mapper, metrics);
    }
}
