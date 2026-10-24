package com.enterprise.oms.common.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Event envelope (contract section 7). JSON field names equal the record component names.
 *
 * @param <T> payload type; use {@code JsonNode} when the type is not known yet (consumers)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        UUID causationId,
        String aggregateType,
        String aggregateId,
        String producer,
        T payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Builds a new envelope with a random eventId and {@code occurredAt = now}. */
    public static <T> EventEnvelope<T> of(String eventType, String aggregateType, String aggregateId,
                                          String producer, String correlationId, UUID causationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, CURRENT_SCHEMA_VERSION, Instant.now(),
                correlationId, causationId, aggregateType, aggregateId, producer, payload);
    }

    /** Convenience for order-aggregate events: aggregateType {@code Order}, aggregateId = orderId. */
    public static <T> EventEnvelope<T> forOrder(String eventType, UUID orderId, String producer,
                                                String correlationId, UUID causationId, T payload) {
        return of(eventType, EventTypes.AGGREGATE_ORDER, orderId.toString(), producer, correlationId, causationId,
                payload);
    }
}
