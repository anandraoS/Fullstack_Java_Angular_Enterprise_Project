package com.enterprise.oms.order.kafka;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.Awaits;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The saga end to end over a real (embedded) Kafka: the test is inventory-service and fulfillment-service. Every
 * assertion on "what order-service said" reads the actual topics.
 */
class OrderSagaKafkaIT extends AbstractOrderKafkaIT {

    @Autowired JdbcTemplate jdbc;

    // ---- happy path ------------------------------------------------------------------------------------------------

    @Test
    void fullHappyPathDrivenBySimulatedInventoryAndFulfillmentReplies() throws Exception {
        Customer customer = newCustomer();
        StubInventoryClient.Product product = newProduct(100);

        // 1. the customer places the order: 202, then the saga validates it asynchronously
        UUID id = placeOrder(customer, product, 3);
        awaitStatus(id, OrderStatus.VALIDATED);

        // 2. order-service published OrderCreated (events topic) and ReserveInventory (command topic), keyed by orderId
        ConsumerRecord<String, String> created = awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_CREATED, id);
        ConsumerRecord<String, String> reserve = awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id);
        assertThat(created.key()).isEqualTo(id.toString());
        assertThat(reserve.key()).isEqualTo(id.toString());
        assertThat(header(reserve, "eventType")).isEqualTo("ReserveInventory");
        assertThat(header(reserve, "schemaVersion")).isEqualTo("1");
        assertThat(header(reserve, "eventId")).isNotBlank();
        assertThat(header(reserve, "correlationId")).isNotBlank();
        assertThat(payloadOf(created).get("totalAmount").decimalValue()).isEqualByComparingTo("30.00");
        JsonNode reservePayload = payloadOf(reserve);
        assertThat(reservePayload.get("items").get(0).get("productId").asText()).isEqualTo(product.id().toString());
        assertThat(reservePayload.get("items").get(0).get("quantity").asInt()).isEqualTo(3);

        // 3. inventory reserves -> CONFIRMED, OrderConfirmed + StartFulfillment published
        UUID reservation = UUID.randomUUID();
        inventoryReplies(TestEvents.reserved(id, reservation, quantityItems(id)));
        awaitStatus(id, OrderStatus.CONFIRMED);
        awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_CONFIRMED, id);
        ConsumerRecord<String, String> start = awaitEvent(Topics.FULFILLMENT_COMMANDS, EventTypes.START_FULFILLMENT, id);
        assertThat(payloadOf(start).get("shippingAddress").get("city").asText()).isEqualTo("Berlin");
        assertThat(payloadOf(start).get("items").get(0).get("sku").asText()).isEqualTo(product.sku());

        // 4. fulfillment progresses and completes -> FULFILLED, OrderFulfilled published
        fulfillmentReplies(TestEvents.fulfillmentStarted(id));
        fulfillmentReplies(TestEvents.fulfillmentCompleted(id, "TRK-IT-1"));
        awaitStatus(id, OrderStatus.FULFILLED);
        ConsumerRecord<String, String> fulfilled = awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_FULFILLED, id);
        assertThat(payloadOf(fulfilled).get("trackingNumber").asText()).isEqualTo("TRK-IT-1");
        assertThat(payloadOf(fulfilled).get("customerEmail").asText()).isEqualTo(customer.getEmail());

        // 5. the customer sees the end state through the API, and the saga is complete
        mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.customer(customer.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FULFILLED"));
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(SagaState.COMPLETED);
        mvc.perform(get("/api/v1/orders/{id}/history", id).header("Authorization", JwtTestTokens.admin()))
                .andExpect(jsonPath("$[*].toStatus").value(org.hamcrest.Matchers.contains("CREATED", "VALIDATED",
                        "INVENTORY_RESERVED", "CONFIRMED", "FULFILLED")));

        // 6. every outbox row was relayed (at-least-once publication marked)
        Awaits.untilAsserted(() -> assertThat(outbox.findAll().stream()
                .filter(o -> o.getAggregateId().equals(id.toString())).allMatch(o -> o.getPublishedAt() != null)).isTrue());
    }

    @Test
    void opsMessagingEndpointReportsTheConsumersWithLag() throws Exception {
        confirmedOrder();   // make sure something was consumed
        Awaits.untilAsserted(() -> mvc.perform(get("/api/v1/orders").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk()));
        Awaits.untilAsserted(() -> mvc.perform(get("/api/v1/ops/messaging").header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lagAvailable").value(true))
                .andExpect(jsonPath("$.processedEvents").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.topics[*].topic").value(org.hamcrest.Matchers.hasItems(Topics.INVENTORY_EVENTS, Topics.FULFILLMENT_EVENTS))));
    }

    // ---- rejection paths -------------------------------------------------------------------------------------------

    @Test
    void insufficientStockReportedByInventoryRejectsTheOrder() throws Exception {
        UUID id = placeOrder(2);
        awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id);
        UUID productId = itemsOf(id).get(0).getProductId();

        inventoryReplies(TestEvents.reservationFailed(id, productId, 2, 1));

        awaitStatus(id, OrderStatus.REJECTED);
        ConsumerRecord<String, String> rejected = awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_REJECTED, id);
        assertThat(payloadOf(rejected).get("reason").asText()).contains("Insufficient stock").contains("requested 2 available 1");
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(SagaState.ABORTED);
    }

    @Test
    void anOrderForAnOutOfStockProductIsRejectedAtValidationWithoutReservingAnything() throws Exception {
        Customer customer = newCustomer();
        StubInventoryClient.Product scarce = newProduct(1);

        UUID id = placeOrder(customer, scarce, 5);

        awaitStatus(id, OrderStatus.REJECTED);
        awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_REJECTED, id);
        assertThat(eventsWithin(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id, Duration.ofSeconds(2))).isEmpty();
    }

    // ---- compensation ----------------------------------------------------------------------------------------------

    @Test
    void cancellingAConfirmedOrderReleasesTheInventoryThenCancels() throws Exception {
        UUID[] confirmed = confirmedOrder();
        UUID id = confirmed[0];
        Customer owner = customers.findById(orders.findById(id).orElseThrow().getCustomerId()).orElseThrow();

        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"IT cancel\"}").header("Authorization", JwtTestTokens.customer(owner.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPENSATION_PENDING"));

        ConsumerRecord<String, String> release = awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id);
        assertThat(payloadOf(release).get("reason").asText()).isEqualTo("CANCELLATION");
        assertThat(eventsWithin(Topics.ORDER_EVENTS, EventTypes.ORDER_CANCELLED, id, Duration.ofSeconds(1)))
                .as("OrderCancelled only after the release is confirmed").isEmpty();

        inventoryReplies(TestEvents.released(id, confirmed[1], "CANCELLATION"));

        awaitStatus(id, OrderStatus.CANCELLED);
        ConsumerRecord<String, String> cancelled = awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_CANCELLED, id);
        assertThat(payloadOf(cancelled).get("reason").asText()).isEqualTo("IT cancel");
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(SagaState.COMPENSATED);
    }

    @Test
    void aReservationThatArrivesAfterTheCancelIsReleasedAndTheOrderStaysCancelled() throws Exception {
        Customer customer = newCustomer();
        UUID id = placeOrder(customer, newProduct(100), 1);
        awaitStatus(id, OrderStatus.VALIDATED);
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.customer(customer.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_CANCELLED, id);

        inventoryReplies(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));

        ConsumerRecord<String, String> release = awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id);
        assertThat(payloadOf(release).get("reason").asText()).isEqualTo("CANCELLATION");
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(eventsWithin(Topics.ORDER_EVENTS, EventTypes.ORDER_CONFIRMED, id, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void fulfillmentFailureCompensatesThenFailsTheOrder() throws Exception {
        UUID[] confirmed = confirmedOrder();
        UUID id = confirmed[0];

        fulfillmentReplies(TestEvents.fulfillmentFailed(id, "carrier rejected the parcel"));

        awaitStatus(id, OrderStatus.COMPENSATION_PENDING);
        ConsumerRecord<String, String> release = awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id);
        assertThat(payloadOf(release).get("reason").asText()).isEqualTo("FULFILLMENT_FAILURE");

        inventoryReplies(TestEvents.released(id, confirmed[1], "FULFILLMENT_FAILURE"));

        awaitStatus(id, OrderStatus.FAILED);
        ConsumerRecord<String, String> failed = awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_FAILED, id);
        assertThat(payloadOf(failed).get("reason").asText()).contains("carrier rejected the parcel");
        mvc.perform(get("/api/v1/admin/orders/failed").param("size", "100").header("Authorization", JwtTestTokens.operator()))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.hasItem(id.toString())));
    }

    @Test
    void anExpiredReservationFailsTheOrder() throws Exception {
        UUID id = placeOrder(1);
        awaitStatus(id, OrderStatus.VALIDATED);

        inventoryReplies(TestEvents.released(id, UUID.randomUUID(), "EXPIRED"));

        awaitStatus(id, OrderStatus.FAILED);
        ConsumerRecord<String, String> failed = awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_FAILED, id);
        assertThat(payloadOf(failed).get("reason").asText()).contains("expired");
    }

    // ---- duplicates, replays, ordering, poison ----------------------------------------------------------------------

    @Test
    void theSameEventDeliveredTwiceIsProcessedOnce() throws Exception {
        UUID id = placeOrder(1);
        awaitStatus(id, OrderStatus.VALIDATED);
        EventEnvelope<?> reserved = TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id));
        long duplicates = messagingMetrics.duplicatesTotal();

        inventoryReplies(reserved);
        inventoryReplies(reserved);     // redelivery of the very same eventId

        awaitStatus(id, OrderStatus.CONFIRMED);
        Awaits.until(Duration.ofSeconds(30), () -> messagingMetrics.duplicatesTotal() >= duplicates + 1);
        assertThat(eventsWithin(Topics.ORDER_EVENTS, EventTypes.ORDER_CONFIRMED, id, Duration.ofSeconds(2))).hasSize(1);
        assertThat(eventsWithin(Topics.FULFILLMENT_COMMANDS, EventTypes.START_FULFILLMENT, id, Duration.ofSeconds(1))).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = 'order-service' and event_id = ?",
                Integer.class, reserved.eventId())).isEqualTo(1);
    }

    @Test
    void anOldEventReplayedUnderANewIdAfterTheOrderAdvancedChangesNothing() throws Exception {
        UUID[] confirmed = confirmedOrder();
        UUID id = confirmed[0];
        fulfillmentReplies(TestEvents.fulfillmentCompleted(id, "TRK-REPLAY"));
        awaitStatus(id, OrderStatus.FULFILLED);
        EventEnvelope<?> replay = TestEvents.withNewId(TestEvents.reserved(id, confirmed[1], quantityItems(id)));
        EventEnvelope<?> replayCompleted = TestEvents.withNewId(TestEvents.fulfillmentCompleted(id, "TRK-REPLAY-2"));

        inventoryReplies(replay);
        fulfillmentReplies(replayCompleted);

        // both were consumed (processed marker written) ...
        Awaits.untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, replay.eventId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, replayCompleted.eventId())).isEqualTo(1);
        });
        // ... but nothing happened: still FULFILLED, one StartFulfillment, one OrderFulfilled with the FIRST tracking number
        assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(eventsWithin(Topics.FULFILLMENT_COMMANDS, EventTypes.START_FULFILLMENT, id, Duration.ofSeconds(1))).hasSize(1);
        List<ConsumerRecord<String, String>> fulfilled = eventsWithin(Topics.ORDER_EVENTS, EventTypes.ORDER_FULFILLED, id, Duration.ofSeconds(1));
        assertThat(fulfilled).hasSize(1);
        assertThat(payloadOf(fulfilled.get(0)).get("trackingNumber").asText()).isEqualTo("TRK-REPLAY");
    }

    @Test
    void eventsArrivingOutOfOrderAreToleratedWithoutDeadLettering() throws Exception {
        UUID id = placeOrder(1);
        awaitStatus(id, OrderStatus.VALIDATED);
        long dead = messagingMetrics.deadLetteredTotal();

        // a fulfillment event for an order that is not even confirmed yet: ignored (stale), not an error
        fulfillmentReplies(TestEvents.fulfillmentCompleted(id, "TRK-TOO-EARLY"));
        inventoryReplies(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));
        awaitStatus(id, OrderStatus.CONFIRMED);
        // 'completed' before 'started' is fine too
        fulfillmentReplies(TestEvents.fulfillmentCompleted(id, "TRK-OOO"));
        awaitStatus(id, OrderStatus.FULFILLED);
        fulfillmentReplies(TestEvents.fulfillmentStarted(id));     // late 'started'
        fulfillmentReplies(TestEvents.fulfillmentFailed(id, "stale"));   // and a stale failure

        Awaits.untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where entity_id = ? and action = 'TRANSITION_IGNORED'", Integer.class,
                id.toString())).isGreaterThanOrEqualTo(2));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(messagingMetrics.deadLetteredTotal()).isEqualTo(dead);
        assertThat(eventsWithin(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void poisonMessagesGoToTheDeadLetterTopicAndDoNotBlockThePartition() throws Exception {
        long dead = messagingMetrics.deadLetteredTotal();
        String marker = "poison-" + UUID.randomUUID();
        UUID key = UUID.randomUUID();

        // 1. not JSON at all
        send(Topics.INVENTORY_EVENTS, key.toString(), "{this is not json " + marker);
        // 2. valid envelope but a payload that cannot be converted (orderId is not a UUID)
        String badPayload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"InventoryReserved\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"correlationId\":\"c\",\"aggregateType\":\"Order\","
                + "\"aggregateId\":\"" + key + "\",\"producer\":\"inventory-service\",\"payload\":{\"orderId\":\"" + marker + "\"}}";
        send(Topics.INVENTORY_EVENTS, key.toString(), badPayload);
        // 3. a healthy event for a real order on the SAME key/partition behind them
        UUID[] confirmed = confirmedOrder();
        send(Topics.INVENTORY_EVENTS, confirmed[0].toString(), "{broken again " + marker);
        fulfillmentReplies(TestEvents.fulfillmentCompleted(confirmed[0], "TRK-AFTER-POISON"));

        ConsumerRecord<String, String> dlt1 = awaitRecord(Topics.INVENTORY_EVENTS + ".DLT", r -> r.value().contains("{this is not json " + marker));
        ConsumerRecord<String, String> dlt2 = awaitRecord(Topics.INVENTORY_EVENTS + ".DLT", r -> r.value().contains(marker) && r.value().contains("InventoryReserved"));
        assertThat(dlt1.key()).isEqualTo(key.toString());
        assertThat(header(dlt1, "x-exception-class")).contains("MalformedEventException");
        assertThat(header(dlt1, "x-original-topic")).isEqualTo(Topics.INVENTORY_EVENTS);
        assertThat(header(dlt2, "x-exception-class")).isNotBlank();
        awaitStatus(confirmed[0], OrderStatus.FULFILLED);   // the partition was not blocked by the poison
        Awaits.until(Duration.ofSeconds(30), () -> messagingMetrics.deadLetteredTotal() >= dead + 3);
    }
}
