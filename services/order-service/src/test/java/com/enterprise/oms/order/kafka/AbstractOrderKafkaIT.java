package com.enterprise.oms.order.kafka;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.common.outbox.OutboxEventRepository;
import com.enterprise.oms.order.domain.CustomerTier;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.repository.CustomerRepository;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.saga.OrderSagaOrchestrator;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.order.support.TestInventoryConfig;
import com.enterprise.oms.testsupport.AbstractKafkaIT;
import com.enterprise.oms.testsupport.Awaits;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Embedded KRaft Kafka + the full application: listeners, outbox relay and the async saga start are ON. The test plays
 * inventory-service and fulfillment-service: it consumes the commands order-service publishes and produces replies.
 * All Kafka IT classes inherit this exact configuration, so they share one Spring context and one broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInventoryConfig.class)
@TestPropertySource(properties = {
        "oms.saga.auto-start=true",
        "oms.saga.listeners-enabled=true",
        "oms.outbox.relay-enabled=true",
        "oms.outbox.poll-interval-ms=100",
        "oms.messaging.admin-timeout-ms=3000"})
public abstract class AbstractOrderKafkaIT extends AbstractKafkaIT {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected CustomerRepository customers;
    @Autowired protected OrderRepository orders;
    @Autowired protected SagaInstanceRepository sagas;
    @Autowired protected OutboxEventRepository outbox;
    @Autowired protected StubInventoryClient inventory;
    @Autowired protected MessagingMetrics messagingMetrics;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected OrderSagaOrchestrator orchestrator;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ---- data and API ----------------------------------------------------------------------------------------------

    protected Customer newCustomer() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return customers.save(new Customer(UUID.randomUUID(), "Kafka Customer " + id, "k-" + id + "@example.test",
                CustomerTier.STANDARD, Instant.now()));
    }

    protected StubInventoryClient.Product newProduct(int available) {
        return inventory.addProduct("SKU-K" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 4),
                "10.00", available);
    }

    protected static String orderJson(UUID customerId, UUID productId, int quantity) {
        return """
                {"customerId":"%s","items":[{"productId":"%s","quantity":%d}],
                 "shippingAddress":{"line1":"1 Kafka Street","city":"Berlin","postalCode":"10115","country":"DE"}}
                """.formatted(customerId, productId, quantity);
    }

    protected UUID placeOrder(Customer customer, StubInventoryClient.Product product, int quantity) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", JwtTestTokens.customer(customer.getId()))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                        .content(orderJson(customer.getId(), product.id(), quantity))).andReturn();
        if (r.getResponse().getStatus() != 202) {
            throw new AssertionError("create failed: " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    protected UUID placeOrder(int quantity) throws Exception {
        return placeOrder(newCustomer(), newProduct(100), quantity);
    }

    protected OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).map(o -> o.getStatus()).orElse(null);
    }

    protected void awaitStatus(UUID orderId, OrderStatus expected) {
        Awaits.until(Duration.ofSeconds(40), () -> statusOf(orderId) == expected);
    }

    protected List<com.enterprise.oms.order.entity.OrderItem> itemsOf(UUID orderId) {
        return tx.execute(s -> {
            var o = orders.findWithItemsById(orderId).orElseThrow();
            return new ArrayList<>(o.getItems());
        });
    }

    protected List<com.enterprise.oms.common.events.EventPayloads.QuantityItem> quantityItems(UUID orderId) {
        return itemsOf(orderId).stream()
                .map(i -> new com.enterprise.oms.common.events.EventPayloads.QuantityItem(i.getProductId(), i.getQuantity())).toList();
    }

    // ---- watching what order-service publishes ---------------------------------------------------------------------

    protected static boolean isEvent(ConsumerRecord<String, String> r, String eventType, UUID orderId) {
        return r.value() != null && r.value().contains("\"eventType\":\"" + eventType + "\"")
                && r.value().contains(orderId.toString());
    }

    /** Waits for the (first) record of that type for the order on the topic. */
    protected ConsumerRecord<String, String> awaitEvent(String topic, String eventType, UUID orderId) {
        return awaitRecord(topic, r -> isEvent(r, eventType, orderId));
    }

    protected JsonNode payloadOf(ConsumerRecord<String, String> record) {
        return EventJson.parseEnvelope(mapper, record.value()).payload();
    }

    /** All records of that type for the order seen within {@code window} (poll until quiet). */
    protected List<ConsumerRecord<String, String>> eventsWithin(String topic, String eventType, UUID orderId, Duration window) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            long end = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (isEvent(r, eventType, orderId)) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    protected static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // ---- playing the other services -------------------------------------------------------------------------------

    protected void publish(String topic, EventEnvelope<?> event) throws Exception {
        send(topic, event.aggregateId(), TestEvents.json(event));
    }

    protected void inventoryReplies(EventEnvelope<?> event) throws Exception {
        publish(com.enterprise.oms.common.events.Topics.INVENTORY_EVENTS, event);
    }

    protected void fulfillmentReplies(EventEnvelope<?> event) throws Exception {
        publish(com.enterprise.oms.common.events.Topics.FULFILLMENT_EVENTS, event);
    }

    /** Drives a new order to CONFIRMED the way the real inventory-service would. Returns {orderId, reservationId}. */
    protected UUID[] confirmedOrder() throws Exception {
        UUID id = placeOrder(2);
        awaitEvent(com.enterprise.oms.common.events.Topics.INVENTORY_COMMANDS, "ReserveInventory", id);
        UUID reservation = UUID.randomUUID();
        inventoryReplies(TestEvents.reserved(id, reservation, quantityItems(id)));
        awaitStatus(id, OrderStatus.CONFIRMED);
        return new UUID[]{id, reservation};
    }
}
