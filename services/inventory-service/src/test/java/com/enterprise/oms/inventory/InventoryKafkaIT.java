package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.idempotency.ProcessedEvent;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.testsupport.AbstractKafkaIT;
import com.enterprise.oms.testsupport.Awaits;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End-to-end on embedded KRaft Kafka: commands and events in, outbox relay out, idempotent consumers, DLT, expiry.
 * (Not run in the surefire phase: *IT classes are executed by failsafe during {@code mvn verify}.)
 */
@SpringBootTest(properties = {
        "oms.inventory.listeners-enabled=true",
        "oms.inventory.expiry-scheduler-enabled=true",
        "oms.inventory.expiry-scheduler-interval-ms=500",
        "oms.outbox.relay-enabled=true",
        "oms.outbox.poll-interval-ms=100",
        "spring.datasource.url=jdbc:h2:mem:inventory_kafka;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class InventoryKafkaIT extends AbstractKafkaIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONSUMER = "inventory-service";

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired MessagingMetrics metrics;
    @Autowired ProcessedEventRepository processed;

    // ------------------------------------------------------------------ fixtures

    private UUID newProduct(int onHand) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO products (id, sku, name, description, category, price, currency, active, created_at, updated_at, version) "
                + "VALUES (?, ?, 'kafka test', 'd', 'Kafka', 5.00, 'USD', TRUE, ?, ?, 0)", id,
                "K-" + id.toString().substring(0, 8).toUpperCase(), now, now);
        jdbc.update("INSERT INTO stock (product_id, on_hand, reserved, version, updated_at) VALUES (?, ?, 0, 0, ?)", id, onHand, now);
        return id;
    }

    private int onHand(UUID p) { return jdbc.queryForObject("SELECT on_hand FROM stock WHERE product_id = ?", Integer.class, p); }
    private int reserved(UUID p) { return jdbc.queryForObject("SELECT reserved FROM stock WHERE product_id = ?", Integer.class, p); }

    private String status(UUID order) {
        List<String> s = jdbc.queryForList("SELECT status FROM inventory_reservations WHERE order_id = ?", String.class, order);
        return s.isEmpty() ? null : s.get(0);
    }

    private int reservationRows(UUID order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations WHERE order_id = ?", Integer.class, order);
    }

    private int outboxRows(UUID order, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?", Integer.class,
                order.toString(), type);
    }

    private static EventEnvelope<?> envelope(String type, UUID orderId, String correlationId, Object payload) {
        return EventEnvelope.forOrder(type, orderId, "order-service", correlationId, null, payload);
    }

    private EventEnvelope<?> reserveCmd(UUID order, int ttl, QuantityItem... items) {
        return envelope(EventTypes.RESERVE_INVENTORY, order, "corr-" + order,
                new EventPayloads.ReserveInventory(order, List.of(items), ttl));
    }

    private void publish(String topic, UUID order, EventEnvelope<?> env) throws Exception {
        send(topic, order.toString(), EventJson.toJson(env));
    }

    private ConsumerRecord<String, String> awaitInventoryEvent(UUID order, String type) {
        return awaitRecord(Topics.INVENTORY_EVENTS, r -> r.key() != null && r.key().equals(order.toString())
                && r.value().contains("\"eventType\":\"" + type + "\""));
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ reserve

    @Test
    void reserveCommandReservesStockAndPublishesInventoryReservedWithEnvelopeAndKey() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        EventEnvelope<?> cmd = reserveCmd(order, 600, new QuantityItem(p, 4));

        publish(Topics.INVENTORY_COMMANDS, order, cmd);

        ConsumerRecord<String, String> rec = awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);
        JsonNode env = JSON.readTree(rec.value());
        assertThat(rec.key()).isEqualTo(order.toString());
        assertThat(env.get("producer").asText()).isEqualTo("inventory-service");
        assertThat(env.get("aggregateType").asText()).isEqualTo("Order");
        assertThat(env.get("aggregateId").asText()).isEqualTo(order.toString());
        assertThat(env.get("correlationId").asText()).isEqualTo("corr-" + order);
        assertThat(env.get("causationId").asText()).isEqualTo(cmd.eventId().toString());
        assertThat(env.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(env.at("/payload/orderId").asText()).isEqualTo(order.toString());
        assertThat(env.at("/payload/items/0/quantity").asInt()).isEqualTo(4);
        assertThat(env.at("/payload/expiresAt").asText()).isNotBlank();
        assertThat(header(rec, "eventType")).isEqualTo("InventoryReserved");
        assertThat(header(rec, "correlationId")).isEqualTo("corr-" + order);
        assertThat(status(order)).isEqualTo("RESERVED");
        assertThat(reserved(p)).isEqualTo(4);
        assertThat(processed.existsById(new ProcessedEvent.Key(CONSUMER, cmd.eventId()))).as("marker in the same tx").isTrue();
    }

    @Test
    void insufficientStockPublishesInventoryReservationFailedWithShortages() throws Exception {
        UUID p = newProduct(3);
        UUID order = UUID.randomUUID();

        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 5)));

        JsonNode env = JSON.readTree(awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVATION_FAILED).value());
        assertThat(env.at("/payload/reason").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(env.at("/payload/shortages/0/productId").asText()).isEqualTo(p.toString());
        assertThat(env.at("/payload/shortages/0/requested").asInt()).isEqualTo(5);
        assertThat(env.at("/payload/shortages/0/available").asInt()).isEqualTo(3);
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(reserved(p)).isZero();
    }

    @Test
    void redeliveryOfTheSameEventIsAppliedOnce() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        String json = EventJson.toJson(reserveCmd(order, 600, new QuantityItem(p, 2)));
        long dupBefore = metrics.duplicatesTotal();

        send(Topics.INVENTORY_COMMANDS, order.toString(), json);
        send(Topics.INVENTORY_COMMANDS, order.toString(), json);

        Awaits.until(() -> metrics.duplicatesTotal() >= dupBefore + 1);
        assertThat(reserved(p)).isEqualTo(2);
        assertThat(reservationRows(order)).isEqualTo(1);
        assertThat(outboxRows(order, EventTypes.INVENTORY_RESERVED)).as("effect (and its event) happened once").isEqualTo(1);
    }

    @Test
    void aSecondCommandForTheSameOrderWithANewEventIdReturnsTheOriginalOutcome() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 2)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);
        String reservationId = jdbc.queryForObject("SELECT id FROM inventory_reservations WHERE order_id = ?", String.class, order);

        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 2)));   // saga recovery re-send

        Awaits.untilAsserted(() -> assertThat(outboxRows(order, EventTypes.INVENTORY_RESERVED)).isEqualTo(2));
        assertThat(reserved(p)).as("stock held once").isEqualTo(2);
        assertThat(reservationRows(order)).isEqualTo(1);
        List<String> payloads = jdbc.queryForList("SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = 'InventoryReserved'",
                String.class, order.toString());
        assertThat(payloads).allMatch(s -> s.contains(reservationId));
    }

    // ------------------------------------------------------------------ release / confirm / cancel / ship

    @Test
    void releaseCommandGivesStockBackAndIsIdempotent() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 4)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);

        publish(Topics.INVENTORY_COMMANDS, order, envelope(EventTypes.RELEASE_INVENTORY, order, "corr-rel",
                new EventPayloads.ReleaseInventory(order, "FULFILLMENT_FAILURE")));
        JsonNode released = JSON.readTree(awaitInventoryEvent(order, EventTypes.INVENTORY_RELEASED).value());
        assertThat(released.at("/payload/reason").asText()).isEqualTo("FULFILLMENT_FAILURE");
        assertThat(status(order)).isEqualTo("RELEASED");
        assertThat(reserved(p)).isZero();

        publish(Topics.INVENTORY_COMMANDS, order, envelope(EventTypes.RELEASE_INVENTORY, order, "corr-rel2",
                new EventPayloads.ReleaseInventory(order, "FULFILLMENT_FAILURE")));   // new eventId: saga retry
        Awaits.untilAsserted(() -> assertThat(outboxRows(order, EventTypes.INVENTORY_RELEASED)).isEqualTo(2));
        assertThat(reserved(p)).as("never given back twice").isZero();
        assertThat(onHand(p)).isEqualTo(10);
    }

    @Test
    void orderConfirmedCommitsTheReservationAndClearsItsTtl() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 3)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);

        publish(Topics.ORDER_EVENTS, order, envelope(EventTypes.ORDER_CONFIRMED, order, "corr-conf",
                new EventPayloads.OrderConfirmed(order, UUID.randomUUID(), "a@example.test", new java.math.BigDecimal("15.00"))));

        Awaits.untilAsserted(() -> assertThat(status(order)).isEqualTo("COMMITTED"));
        assertThat(jdbc.queryForObject("SELECT expires_at IS NULL FROM inventory_reservations WHERE order_id = ?", Boolean.class, order)).isTrue();
        assertThat(reserved(p)).isEqualTo(3);
    }

    @Test
    void orderCancelledReleasesTheReservation() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 3)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);

        publish(Topics.ORDER_EVENTS, order, envelope(EventTypes.ORDER_CANCELLED, order, "corr-canc",
                new EventPayloads.OrderCancelled(order, UUID.randomUUID(), "a@example.test", "customer request")));

        Awaits.untilAsserted(() -> assertThat(status(order)).isEqualTo("RELEASED"));
        assertThat(reserved(p)).isZero();
        assertThat(jdbc.queryForObject("SELECT release_reason FROM inventory_reservations WHERE order_id = ?", String.class, order))
                .isEqualTo("CANCELLATION");
    }

    @Test
    void fulfillmentCompletedShipsTheStockExactlyOnce() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 3)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);
        publish(Topics.ORDER_EVENTS, order, envelope(EventTypes.ORDER_CONFIRMED, order, "c",
                new EventPayloads.OrderConfirmed(order, UUID.randomUUID(), "a@example.test", java.math.BigDecimal.TEN)));
        Awaits.untilAsserted(() -> assertThat(status(order)).isEqualTo("COMMITTED"));

        UUID fulfillment = UUID.randomUUID();
        publish(Topics.FULFILLMENT_EVENTS, order, envelope(EventTypes.FULFILLMENT_STARTED, order, "c",
                new EventPayloads.FulfillmentStarted(order, fulfillment)));          // ignored by inventory
        publish(Topics.FULFILLMENT_EVENTS, order, envelope(EventTypes.FULFILLMENT_COMPLETED, order, "c",
                new EventPayloads.FulfillmentCompleted(order, fulfillment, "TRK123")));
        publish(Topics.FULFILLMENT_EVENTS, order, envelope(EventTypes.FULFILLMENT_COMPLETED, order, "c",
                new EventPayloads.FulfillmentCompleted(order, fulfillment, "TRK123")));  // duplicate delivery, new eventId

        Awaits.untilAsserted(() -> assertThat(onHand(p)).isEqualTo(7));
        Awaits.neverWithin(java.time.Duration.ofSeconds(2), () -> onHand(p) != 7);
        assertThat(reserved(p)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type = 'SHIP'", Integer.class, p))
                .isEqualTo(1);
    }

    @Test
    void unrelatedEventTypesOnSharedTopicsAreIgnoredNotDeadLettered() throws Exception {
        UUID order = UUID.randomUUID();
        long ignoredBefore = metrics.ignoredTotal();
        long dltBefore = metrics.deadLetteredTotal();

        publish(Topics.ORDER_EVENTS, order, envelope(EventTypes.ORDER_CREATED, order, "c",
                new EventPayloads.OrderCreated(order, UUID.randomUUID(), "a@example.test", java.math.BigDecimal.ONE, "USD", List.of(), "X")));

        Awaits.until(() -> metrics.ignoredTotal() >= ignoredBefore + 1);
        assertThat(metrics.deadLetteredTotal()).isEqualTo(dltBefore);
    }

    // ------------------------------------------------------------------ poison messages

    @Test
    void invalidCommandsGoStraightToTheDltWithoutRetriesAndDoNotBlockThePartition() throws Exception {
        UUID p = newProduct(10);
        UUID badOrder = UUID.randomUUID();
        EventEnvelope<?> zeroQty = reserveCmd(badOrder, 600, new QuantityItem(p, 0));
        long retriesBefore = metrics.retriesTotal();
        long dltBefore = metrics.deadLetteredTotal();

        publish(Topics.INVENTORY_COMMANDS, badOrder, zeroQty);
        UUID goodOrder = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, badOrder, reserveCmd(goodOrder, 600, new QuantityItem(p, 1)));   // same key/partition

        ConsumerRecord<String, String> dlt = awaitRecord(Topics.dlt(Topics.INVENTORY_COMMANDS),
                r -> r.value().contains(zeroQty.eventId().toString()));
        assertThat(header(dlt, "x-exception-class")).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(header(dlt, "x-original-topic")).isEqualTo(Topics.INVENTORY_COMMANDS);
        awaitInventoryEvent(goodOrder, EventTypes.INVENTORY_RESERVED);
        assertThat(metrics.retriesTotal()).as("IllegalArgumentException is non-retryable").isEqualTo(retriesBefore);
        Awaits.until(() -> metrics.deadLetteredTotal() >= dltBefore + 1);
        assertThat(reservationRows(badOrder)).as("rolled back with the claim").isZero();
        assertThat(reserved(p)).isEqualTo(1);
    }

    @Test
    void malformedJsonAndUnknownReleaseReasonsAreDeadLettered() throws Exception {
        UUID order = UUID.randomUUID();
        String garbage = "{ definitely not json " + order;
        send(Topics.INVENTORY_COMMANDS, order.toString(), garbage);
        ConsumerRecord<String, String> dlt = awaitRecord(Topics.dlt(Topics.INVENTORY_COMMANDS), r -> garbage.equals(r.value()));
        assertThat(header(dlt, "x-exception-class")).contains("MalformedEventException");

        EventEnvelope<?> badReason = envelope(EventTypes.RELEASE_INVENTORY, order, "c", new EventPayloads.ReleaseInventory(order, "BECAUSE"));
        publish(Topics.INVENTORY_COMMANDS, order, badReason);
        awaitRecord(Topics.dlt(Topics.INVENTORY_COMMANDS), r -> r.value().contains(badReason.eventId().toString()));
        assertThat(reservationRows(order)).isZero();
    }

    // ------------------------------------------------------------------ expiry

    @Test
    void anUnconfirmedReservationExpiresAndTheServiceEmitsInventoryReleasedWithReasonExpired() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 1, new QuantityItem(p, 5)));   // 1 second TTL
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);

        JsonNode released = JSON.readTree(awaitInventoryEvent(order, EventTypes.INVENTORY_RELEASED).value());

        assertThat(released.at("/payload/reason").asText()).isEqualTo("EXPIRED");
        assertThat(released.at("/payload/orderId").asText()).isEqualTo(order.toString());
        assertThat(status(order)).isEqualTo("EXPIRED");
        assertThat(reserved(p)).isZero();
    }

    @Test
    void aCommittedReservationDoesNotExpire() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 2, new QuantityItem(p, 5)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);
        publish(Topics.ORDER_EVENTS, order, envelope(EventTypes.ORDER_CONFIRMED, order, "c",
                new EventPayloads.OrderConfirmed(order, UUID.randomUUID(), "a@example.test", java.math.BigDecimal.TEN)));
        Awaits.untilAsserted(() -> assertThat(status(order)).isEqualTo("COMMITTED"));

        Awaits.neverWithin(java.time.Duration.ofSeconds(4), () -> !"COMMITTED".equals(status(order)));
        assertThat(reserved(p)).isEqualTo(5);
        assertThat(outboxRows(order, EventTypes.INVENTORY_RELEASED)).isZero();
    }

    // ------------------------------------------------------------------ ops / metrics

    @Test
    void opsMessagingReportsLagForTheConsumedTopicsAndPrometheusExposesTheMetrics() throws Exception {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        publish(Topics.INVENTORY_COMMANDS, order, reserveCmd(order, 600, new QuantityItem(p, 1)));
        awaitInventoryEvent(order, EventTypes.INVENTORY_RESERVED);

        Awaits.untilAsserted(() -> {
            JsonNode s = JSON.readTree(mvc.perform(get("/api/v1/ops/messaging").header("Authorization", JwtTestTokens.operator()))
                    .andReturn().getResponse().getContentAsString());
            assertThat(s.get("service").asText()).isEqualTo("inventory-service");
            assertThat(s.get("consumerGroup").asText()).isEqualTo("inventory-service");
            assertThat(s.get("lagAvailable").asBoolean()).isTrue();
            assertThat(s.get("processedEvents").asLong()).isPositive();
            assertThat(s.get("topics")).hasSize(3);
            assertThat(s.get("dltTopics")).hasSize(3);
        });

        String text = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(text).contains("oms_inventory_reservations_total{").contains("outcome=\"reserved\"")
                .contains("oms_inventory_reservation_conflicts_total")
                .contains("oms_inventory_reservation_duration_seconds_bucket")
                .contains("oms_events_processed_total").contains("oms_events_duplicates_total")
                .contains("oms_events_dead_lettered_total").contains("oms_outbox_pending")
                .contains("http_server_requests_seconds_bucket").contains("service=\"inventory-service\"");
    }
}
