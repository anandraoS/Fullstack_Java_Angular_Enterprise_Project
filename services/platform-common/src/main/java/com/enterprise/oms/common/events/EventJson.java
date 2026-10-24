package com.enterprise.oms.common.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson helper for events: JavaTimeModule, ISO-8601 dates, tolerant reader (unknown properties ignored) and
 * parsing of raw Kafka values into {@code EventEnvelope<JsonNode>} where any failure becomes the non-retryable
 * {@link MalformedEventException}.
 */
public final class EventJson {

    private static final ObjectMapper SHARED = newMapper();

    private EventJson() {
    }

    /** Configures an existing mapper the way events need it (also used by the Boot Jackson customizer). */
    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // keep money scale (12.50 stays 12.50 when going through JsonNode)
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        return mapper;
    }

    public static ObjectMapper newMapper() {
        return configure(JsonMapper.builder().build());
    }

    /** Shared, thread-safe, fully configured mapper. */
    public static ObjectMapper mapper() {
        return SHARED;
    }

    public static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public static String toJson(Object value) {
        return toJson(SHARED, value);
    }

    /**
     * Parses a raw record value. Throws {@link MalformedEventException} for invalid JSON or an envelope missing
     * eventId/eventType/payload.
     */
    public static EventEnvelope<JsonNode> parseEnvelope(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            throw new MalformedEventException("Empty event value");
        }
        try {
            EventEnvelope<JsonNode> env = mapper.readValue(json,
                    mapper.getTypeFactory().constructParametricType(EventEnvelope.class, JsonNode.class));
            if (env == null || env.eventId() == null || env.eventType() == null || env.eventType().isBlank()) {
                throw new MalformedEventException("Envelope missing eventId or eventType");
            }
            return env;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MalformedEventException("Invalid event JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static EventEnvelope<JsonNode> parseEnvelope(String json) {
        return parseEnvelope(SHARED, json);
    }

    /** Converts the untyped payload to a record; failure is non-retryable ({@link MalformedEventException}). */
    public static <P> P payloadAs(ObjectMapper mapper, EventEnvelope<JsonNode> envelope, Class<P> type) {
        try {
            if (envelope.payload() == null || envelope.payload().isNull()) {
                throw new MalformedEventException("Event " + envelope.eventId() + " has no payload");
            }
            return mapper.treeToValue(envelope.payload(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MalformedEventException("Invalid payload for " + envelope.eventType() + ": "
                    + e.getOriginalMessage(), e);
        }
    }
}
