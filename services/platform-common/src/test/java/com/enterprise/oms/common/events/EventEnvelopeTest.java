package com.enterprise.oms.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    @Test
    void roundTripPreservesEveryFieldAndUsesIsoDates() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new EventPayloads.OrderCreated(orderId, UUID.randomUUID(), "a@example.test",
                new BigDecimal("12.50"), "USD",
                List.of(new EventPayloads.PricedItem(UUID.randomUUID(), "SKU-1001", 2, new BigDecimal("6.25"))), "Springfield");
        EventEnvelope<EventPayloads.OrderCreated> env = EventEnvelope.forOrder(EventTypes.ORDER_CREATED, orderId,
                "order-service", "corr-1", UUID.randomUUID(), payload);

        String json = EventJson.toJson(env);
        assertThat(json).contains("\"eventType\":\"OrderCreated\"").contains("\"schemaVersion\":1")
                .contains("\"aggregateType\":\"Order\"").contains("\"aggregateId\":\"" + orderId + "\"");
        assertThat(EventJson.mapper().readTree(json).get("occurredAt").isTextual()).isTrue();

        EventEnvelope<JsonNode> parsed = EventJson.parseEnvelope(json);
        assertThat(parsed.eventId()).isEqualTo(env.eventId());
        assertThat(parsed.causationId()).isEqualTo(env.causationId());
        assertThat(parsed.occurredAt()).isEqualTo(env.occurredAt());
        assertThat(parsed.producer()).isEqualTo("order-service");
        EventPayloads.OrderCreated back = EventJson.payloadAs(EventJson.mapper(), parsed, EventPayloads.OrderCreated.class);
        assertThat(back).isEqualTo(payload);
    }

    @Test
    void tolerantReaderIgnoresUnknownFieldsAndNullCausation() {
        String id = UUID.randomUUID().toString();
        String json = "{\"eventId\":\"" + id + "\",\"eventType\":\"InventoryReleased\",\"schemaVersion\":2,"
                + "\"occurredAt\":\"2025-01-01T10:00:00Z\",\"correlationId\":\"c\",\"causationId\":null,"
                + "\"aggregateType\":\"Order\",\"aggregateId\":\"x\",\"producer\":\"p\",\"futureField\":{\"a\":1},"
                + "\"payload\":{\"orderId\":\"" + UUID.randomUUID() + "\",\"reservationId\":null,\"reason\":\"EXPIRED\",\"extra\":true}}";
        EventEnvelope<JsonNode> env = EventJson.parseEnvelope(json);
        assertThat(env.causationId()).isNull();
        assertThat(env.occurredAt()).isEqualTo(Instant.parse("2025-01-01T10:00:00Z"));
        EventPayloads.InventoryReleased p = EventJson.payloadAs(EventJson.mapper(), env, EventPayloads.InventoryReleased.class);
        assertThat(p.reason()).isEqualTo(EventPayloads.ReleaseReasons.EXPIRED);
    }

    @Test
    void malformedInputIsNonRetryable() {
        assertThatThrownBy(() -> EventJson.parseEnvelope("{not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parseEnvelope("")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parseEnvelope("{\"eventType\":\"X\"}")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parseEnvelope("{\"eventId\":\"not-a-uuid\",\"eventType\":\"X\"}"))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void everyEventTypeHasTopicAndPayloadClass() {
        List<String> types = List.of(EventTypes.ORDER_CREATED, EventTypes.ORDER_CONFIRMED, EventTypes.ORDER_CANCELLED,
                EventTypes.ORDER_REJECTED, EventTypes.ORDER_FULFILLED, EventTypes.ORDER_FAILED,
                EventTypes.RESERVE_INVENTORY, EventTypes.RELEASE_INVENTORY, EventTypes.INVENTORY_RESERVED,
                EventTypes.INVENTORY_RESERVATION_FAILED, EventTypes.INVENTORY_RELEASED, EventTypes.START_FULFILLMENT,
                EventTypes.FULFILLMENT_STARTED, EventTypes.FULFILLMENT_COMPLETED, EventTypes.FULFILLMENT_FAILED);
        for (String t : types) {
            assertThat(EventTypes.topicFor(t)).as(t).isPresent();
            assertThat(EventTypes.payloadClassFor(t)).as(t).isPresent();
        }
        assertThat(EventTypes.topicFor(EventTypes.RESERVE_INVENTORY)).contains(Topics.INVENTORY_COMMANDS);
        assertThat(EventTypes.topicFor(EventTypes.FULFILLMENT_FAILED)).contains(Topics.FULFILLMENT_EVENTS);
        assertThat(EventTypes.topicFor("Nope")).isEmpty();
        assertThat(Topics.ALL_WITH_DLT).hasSize(10).contains("oms.order.events.v1.DLT");
    }
}
