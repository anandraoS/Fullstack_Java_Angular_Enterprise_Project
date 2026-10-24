package com.enterprise.oms.common.outbox;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventTypes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends events to the outbox. {@code append} REQUIRES an active transaction (MANDATORY propagation): the row
 * must commit or roll back together with the business change. Publishing is done by {@link OutboxRelay}.
 */
public class OutboxService {

    private static final TypeReference<Map<String, String>> HEADER_MAP = new TypeReference<>() { };

    private final OutboxEventRepository repository;
    private final ObjectMapper mapper;
    private final AtomicLong lastPending = new AtomicLong();
    private volatile long lastPendingAt;

    public OutboxService(OutboxEventRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Publishes to the topic owned by the event type (see {@link EventTypes#topicFor}), key = aggregateId. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(EventEnvelope<?> envelope) {
        String topic = EventTypes.topicFor(envelope.eventType()).orElseThrow(() ->
                new IllegalArgumentException("Unknown eventType, pass the topic explicitly: " + envelope.eventType()));
        return append(topic, envelope.aggregateId(), envelope, Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, EventEnvelope<?> envelope) {
        return append(topic, key, envelope, Map.of());
    }

    /**
     * @param extraHeaders additional Kafka headers (e.g. traceparent); the standard headers correlationId,
     *                     eventType, schemaVersion and eventId are always added.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, EventEnvelope<?> envelope, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
        if (envelope.correlationId() != null) {
            headers.put("correlationId", envelope.correlationId());
        }
        headers.put("eventType", envelope.eventType());
        headers.put("schemaVersion", String.valueOf(envelope.schemaVersion()));
        headers.put("eventId", envelope.eventId().toString());
        OutboxEvent row = new OutboxEvent(envelope.eventId(), envelope.aggregateType(), envelope.aggregateId(),
                envelope.eventType(), topic, key, EventJson.toJson(mapper, envelope),
                EventJson.toJson(mapper, headers), Instant.now());
        repository.save(row);
        return row.getId();
    }

    /** Parses the stored header JSON. */
    public Map<String, String> headersOf(OutboxEvent event) {
        if (event.getHeaders() == null || event.getHeaders().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(event.getHeaders(), HEADER_MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    /** Number of unpublished rows (cached ~2s; returns the last known value if the DB is unavailable). */
    public long pendingCount() {
        long now = System.currentTimeMillis();
        if (now - lastPendingAt > 2000) {
            try {
                lastPending.set(repository.countByPublishedAtIsNull());
                lastPendingAt = now;
            } catch (RuntimeException ignored) {
                // keep last value
            }
        }
        return lastPending.get();
    }

    /** Forces the next {@link #pendingCount()} to hit the DB. */
    public void invalidatePendingCache() {
        lastPendingAt = 0;
    }
}
