package com.enterprise.oms.order.support;

import com.enterprise.oms.common.audit.AuditEvent;
import com.enterprise.oms.common.audit.AuditEventRepository;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.outbox.OutboxEvent;
import com.enterprise.oms.common.outbox.OutboxEventRepository;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.domain.CustomerTier;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.repository.CustomerRepository;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.repository.SagaStepLogRepository;
import com.enterprise.oms.order.saga.OrderEventRouting;
import com.enterprise.oms.order.saga.OrderSagaOrchestrator;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for the H2 tests: full application context, real security chain and Flyway migrations, stub inventory,
 * listeners / relay / schedulers OFF (tests drive the saga explicitly and read the outbox table). One shared context
 * for every subclass (identical configuration), each test uses its own customers and orders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("test")
@Import(TestInventoryConfig.class)
public abstract class AbstractOrderSpringTest {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected CustomerRepository customers;
    @Autowired protected OrderRepository orders;
    @Autowired protected SagaInstanceRepository sagas;
    @Autowired protected SagaStepLogRepository stepLog;
    @Autowired protected OutboxEventRepository outbox;
    @Autowired protected AuditEventRepository auditEvents;
    @Autowired protected OrderSagaOrchestrator orchestrator;
    @Autowired protected OrderEventRouting routing;
    @Autowired protected StubInventoryClient inventory;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired protected com.enterprise.oms.common.messaging.MessagingMetrics messagingMetrics;
    @Autowired protected com.enterprise.oms.order.service.RetryingTransactions retryTx;

    // ---- data ----------------------------------------------------------------------------------------------------

    protected Customer newCustomer() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return customers.save(new Customer(UUID.randomUUID(), "Customer " + id, "c-" + id + "@example.test",
                CustomerTier.STANDARD, Instant.now()));
    }

    protected static String orderJson(UUID customerId, UUID productId, int quantity) {
        return orderJson(customerId, productId, quantity, "Berlin");
    }

    protected static String orderJson(UUID customerId, UUID productId, int quantity, String city) {
        return """
                {"customerId":"%s","items":[{"productId":"%s","quantity":%d}],
                 "shippingAddress":{"line1":"1 Test Street","city":"%s","postalCode":"10115","country":"DE"}}
                """.formatted(customerId, productId, quantity, city);
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    // ---- API helpers ---------------------------------------------------------------------------------------------

    protected MockHttpServletRequestBuilder createOrder(String bearer, String key, String body) {
        MockHttpServletRequestBuilder b = post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer);
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return b;
    }

    /** Creates an order through the API as the owning customer; returns its id (status CREATED). */
    protected UUID placeOrder(Customer customer, StubInventoryClient.Product product, int quantity) throws Exception {
        MvcResult r = mvc.perform(createOrder(JwtTestTokens.customer(customer.getId()), newKey(),
                orderJson(customer.getId(), product.id(), quantity))).andReturn();
        if (r.getResponse().getStatus() != 202) {
            throw new AssertionError("create failed: " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    // ---- ready-made order states ---------------------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger SKU_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    protected StubInventoryClient.Product newProduct(String price, int available) {
        return inventory.addProduct("SKU-" + SKU_SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 4),
                price, available);
    }

    /** A new order for a new customer, status CREATED. */
    protected UUID createdOrder() throws Exception {
        return placeOrder(newCustomer(), newProduct("10.00", 100), 2);
    }

    /** Order after the VALIDATE step: VALIDATED, OrderCreated + ReserveInventory in the outbox. */
    protected UUID validatedOrder() throws Exception {
        UUID id = createdOrder();
        validate(id);
        return id;
    }

    /** Order after InventoryReserved: CONFIRMED, OrderConfirmed + StartFulfillment in the outbox. */
    protected UUID confirmedOrder() throws Exception {
        UUID id = validatedOrder();
        deliverInventory(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));
        return id;
    }

    // ---- saga helpers --------------------------------------------------------------------------------------------

    /** Runs the VALIDATE step now (auto-start is off in tests). */
    protected void validate(UUID orderId) {
        orchestrator.validate(orderId);
    }

    /** Delivers an event through the real router (idempotent processing in one transaction), no broker needed. */
    protected void deliver(String topic, EventEnvelope<?> event) {
        routing.route(topic, TestEvents.json(event));
    }

    protected void deliverInventory(EventEnvelope<?> event) {
        deliver(Topics.INVENTORY_EVENTS, event);
    }

    protected void deliverFulfillment(EventEnvelope<?> event) {
        deliver(Topics.FULFILLMENT_EVENTS, event);
    }

    protected CustomerOrder order(UUID id) {
        return tx.execute(s -> {
            CustomerOrder o = orders.findWithItemsById(id).orElseThrow();
            o.getItems().size();
            return o;
        });
    }

    protected com.enterprise.oms.order.domain.OrderStatus statusOf(UUID id) {
        return order(id).getStatus();
    }

    protected OrderView view(UUID id) {
        return tx.execute(s -> OrderView.of(orders.findWithItemsById(id).orElseThrow()));
    }

    protected List<EventPayloads.QuantityItem> quantityItems(UUID orderId) {
        return order(orderId).getItems().stream()
                .map(i -> new EventPayloads.QuantityItem(i.getProductId(), i.getQuantity())).toList();
    }

    // ---- outbox / audit inspection -------------------------------------------------------------------------------

    protected List<OutboxEvent> outboxFor(UUID orderId) {
        return outbox.findAll().stream().filter(o -> o.getAggregateId().equals(orderId.toString()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt())).collect(Collectors.toList());
    }

    protected List<String> outboxTypes(UUID orderId) {
        return outboxFor(orderId).stream().map(OutboxEvent::getEventType).toList();
    }

    protected JsonNode outboxPayload(UUID orderId, String eventType) throws Exception {
        OutboxEvent e = outboxFor(orderId).stream().filter(o -> o.getEventType().equals(eventType)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + eventType + " in outbox of " + orderId
                        + " (have " + outboxTypes(orderId) + ")"));
        return EventJson.parseEnvelope(mapper, e.getPayload()).payload();
    }

    protected long outboxCount(UUID orderId, String eventType) {
        return outboxFor(orderId).stream().filter(o -> o.getEventType().equals(eventType)).count();
    }

    protected List<AuditEvent> audit(UUID orderId) {
        return auditEvents.findAll().stream().filter(a -> a.getEntityId().equals(orderId.toString())).toList();
    }

    protected List<String> auditActions(UUID orderId) {
        return audit(orderId).stream().map(AuditEvent::getAction).toList();
    }
}
