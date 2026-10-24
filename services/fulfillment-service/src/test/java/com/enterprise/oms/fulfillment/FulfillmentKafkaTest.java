package com.enterprise.oms.fulfillment;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.fulfillment.config.FulfillmentProperties;
import com.enterprise.oms.fulfillment.domain.Fulfillment;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import com.enterprise.oms.fulfillment.service.FulfillmentRecovery;
import com.enterprise.oms.fulfillment.service.TrackingNumbers;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fulfillment end to end on embedded KRaft Kafka + H2: command in, scheduler-driven lifecycle, events out through the
 * transactional outbox relay, duplicates, poison messages, operator retry and crash recovery.
 */
@SpringBootTest(properties = {
        "oms.outbox.relay-enabled=true",
        "oms.fulfillment.scheduler-enabled=true",
        "oms.fulfillment.step-delay-ms=20",
        "oms.fulfillment.poll-interval-ms=20"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.FULFILLMENT_COMMANDS, Topics.FULFILLMENT_COMMANDS + ".DLT", Topics.FULFILLMENT_EVENTS})
class FulfillmentKafkaTest {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired FulfillmentRepository repository;
    @Autowired FulfillmentProperties props;
    @Autowired FulfillmentRecovery recovery;
    @Autowired AuditService audit;
    @Autowired MessagingMetrics metrics;
    @Autowired MockMvc mvc;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> kafka;

    private Consumer<String, String> events;
    private Consumer<String, String> dlt;
    private final List<ConsumerRecord<String, String>> seenEvents = new ArrayList<>();
    private final List<ConsumerRecord<String, String>> seenDlt = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        events = consumer(Topics.FULFILLMENT_EVENTS);
        dlt = consumer(Topics.FULFILLMENT_COMMANDS + ".DLT");
    }

    @AfterEach
    void close() {
        props.setFailureRate(0.0);
        props.setSchedulerEnabled(true);
        events.close();
        dlt.close();
    }

    private Consumer<String, String> consumer(String topic) {
        Map<String, Object> p = KafkaTestUtils.consumerProps("ful-it-" + UUID.randomUUID(), "true", broker);
        Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(p, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        c.subscribe(List.of(topic));
        return c;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private EventEnvelope<EventPayloads.StartFulfillment> command(UUID orderId, String city, String correlationId) {
        return EventEnvelope.forOrder(EventTypes.START_FULFILLMENT, orderId, "order-service", correlationId, null,
                new EventPayloads.StartFulfillment(orderId,
                        new EventPayloads.Address("1 Main St", city, "12345", "US"),
                        List.of(new EventPayloads.SkuItem(UUID.randomUUID(), "SKU-1001", 1))));
    }

    private void send(String key, String json) throws Exception {
        kafka.send(new ProducerRecord<>(Topics.FULFILLMENT_COMMANDS, key, json)).get();
    }

    private void send(EventEnvelope<?> env) throws Exception {
        send(env.aggregateId(), EventJson.toJson(env));
    }

    private List<EventEnvelope<JsonNode>> eventsFor(UUID orderId) {
        ConsumerRecords<String, String> recs = events.poll(Duration.ofMillis(100));
        recs.forEach(seenEvents::add);
        return seenEvents.stream().filter(r -> orderId.toString().equals(r.key()))
                .map(r -> EventJson.parseEnvelope(r.value())).toList();
    }

    private EventEnvelope<JsonNode> awaitEvent(UUID orderId, String type) {
        List<EventEnvelope<JsonNode>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(40)).until(() -> {
            found.clear();
            eventsFor(orderId).stream().filter(e -> e.eventType().equals(type)).forEach(found::add);
            return !found.isEmpty();
        });
        return found.get(0);
    }

    private ConsumerRecord<String, String> awaitDlt(String needle) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(40)).until(() -> {
            dlt.poll(Duration.ofMillis(200)).forEach(seenDlt::add);
            found.clear();
            seenDlt.stream().filter(r -> r.value() != null && r.value().contains(needle)).forEach(found::add);
            return !found.isEmpty();
        });
        return found.get(0);
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private Fulfillment awaitStatus(UUID orderId, FulfillmentStatus status) {
        await().atMost(Duration.ofSeconds(40)).until(() ->
                repository.findByOrderId(orderId).map(f -> f.getStatus() == status).orElse(false));
        return repository.findByOrderId(orderId).orElseThrow();
    }

    private List<FulfillmentStatus> statuses(UUID orderId) {
        return repositoryEvents(orderId);
    }

    private List<FulfillmentStatus> repositoryEvents(UUID orderId) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s ->
                repository.findByOrderId(orderId).orElseThrow().getEvents().stream()
                        .map(e -> e.getStatus()).toList());
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void happyPathRunsTheWholeLifecycleAndPublishesStartedAndCompleted() throws Exception {
        UUID orderId = UUID.randomUUID();
        var cmd = command(orderId, "Springfield", "corr-ful-happy");

        send(cmd);

        EventEnvelope<JsonNode> started = awaitEvent(orderId, EventTypes.FULFILLMENT_STARTED);
        EventEnvelope<JsonNode> completed = awaitEvent(orderId, EventTypes.FULFILLMENT_COMPLETED);
        Fulfillment f = awaitStatus(orderId, FulfillmentStatus.COMPLETED);

        assertThat(statuses(orderId)).containsExactly(FulfillmentStatus.PENDING, FulfillmentStatus.PICKING,
                FulfillmentStatus.PACKED, FulfillmentStatus.SHIPPED, FulfillmentStatus.COMPLETED);
        assertThat(f.getTrackingNumber()).isEqualTo(TrackingNumbers.forOrder(orderId));
        assertThat(f.getAttempts()).isEqualTo(1);
        assertThat(started.producer()).isEqualTo("fulfillment-service");
        assertThat(started.correlationId()).isEqualTo("corr-ful-happy");
        assertThat(started.causationId()).isEqualTo(cmd.eventId());
        assertThat(started.payload().get("fulfillmentId").asText()).isEqualTo(f.getId().toString());
        assertThat(completed.payload().get("trackingNumber").asText()).isEqualTo(f.getTrackingNumber());
        assertThat(completed.aggregateId()).isEqualTo(orderId.toString());
    }

    @Test
    void failtownFailsAfterThreeAttemptsAndPublishesFulfillmentFailed() throws Exception {
        UUID orderId = UUID.randomUUID();
        send(command(orderId, "FAILTOWN", "corr-ful-fail"));

        EventEnvelope<JsonNode> failed = awaitEvent(orderId, EventTypes.FULFILLMENT_FAILED);
        Fulfillment f = awaitStatus(orderId, FulfillmentStatus.FAILED);

        assertThat(failed.payload().get("attempts").asInt()).isEqualTo(3);
        assertThat(failed.payload().get("reason").asText()).contains("FAILTOWN");
        assertThat(failed.payload().get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(failed.correlationId()).isEqualTo("corr-ful-fail");
        assertThat(f.getAttempts()).isEqualTo(3);
        assertThat(f.getFailureReason()).contains("FAILTOWN");
        assertThat(eventsFor(orderId)).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.FULFILLMENT_STARTED, EventTypes.FULFILLMENT_FAILED);
    }

    @Test
    void duplicateCommandsCreateExactlyOneFulfillmentAndOneStartedEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        var cmd = command(orderId, "Springfield", "corr-ful-dup");
        long dupBefore = metrics.duplicatesTotal();

        send(cmd);
        send(cmd);                                              // redelivery: same eventId
        send(command(orderId, "Springfield", "corr-ful-dup2")); // new eventId, same order: unique order_id no-op

        awaitEvent(orderId, EventTypes.FULFILLMENT_COMPLETED);
        await().atMost(Duration.ofSeconds(20)).until(() -> metrics.duplicatesTotal() >= dupBefore + 1);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(() -> {
            eventsFor(orderId);
            return true;
        });

        assertThat(repository.findAll().stream().filter(f -> f.getOrderId().equals(orderId))).hasSize(1);
        assertThat(eventsFor(orderId)).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.FULFILLMENT_STARTED, EventTypes.FULFILLMENT_COMPLETED);
    }

    @Test
    void poisonCommandsGoToDltAndDoNotCreateFulfillments() throws Exception {
        // 1) payload that cannot be bound (orderId is not a UUID) -> non-retryable conversion error
        UUID envelopeOrder = UUID.randomUUID();
        String badBinding = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"StartFulfillment\","
                + "\"schemaVersion\":1,\"occurredAt\":\"" + Instant.now() + "\",\"correlationId\":\"corr-poison\","
                + "\"aggregateType\":\"Order\",\"aggregateId\":\"" + envelopeOrder + "\",\"producer\":\"order-service\","
                + "\"payload\":{\"orderId\":\"not-a-uuid\"}}";
        send(envelopeOrder.toString(), badBinding);
        ConsumerRecord<String, String> dlt1 = awaitDlt("not-a-uuid");
        assertThat(dlt1.topic()).isEqualTo("oms.fulfillment.commands.v1.DLT");
        assertThat(header(dlt1, "x-original-topic")).isEqualTo(Topics.FULFILLMENT_COMMANDS);
        assertThat(header(dlt1, "x-exception-class")).isNotBlank();

        // 2) valid JSON but missing shipping city -> IllegalArgumentException (non-retryable)
        UUID orderId = UUID.randomUUID();
        var noCity = command(orderId, "", "corr-poison-2");
        send(noCity);
        ConsumerRecord<String, String> dlt2 = awaitDlt(noCity.eventId().toString());
        assertThat(header(dlt2, "x-exception-class")).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(dlt2.key()).isEqualTo(orderId.toString());
        assertThat(repository.existsByOrderId(orderId)).isFalse();

        // 3) garbage that is not JSON at all
        String garbage = "%%not-json-" + UUID.randomUUID();
        send(UUID.randomUUID().toString(), garbage);
        assertThat(awaitDlt(garbage).value()).isEqualTo(garbage);

        // the consumer keeps working afterwards
        UUID healthy = UUID.randomUUID();
        send(command(healthy, "Springfield", "corr-after-poison"));
        awaitEvent(healthy, EventTypes.FULFILLMENT_COMPLETED);
    }

    @Test
    void operatorRetryOfAFailedFulfillmentCompletesItAndPublishesCompleted() throws Exception {
        UUID orderId = UUID.randomUUID();
        props.setFailureRate(1.0); // every attempt fails randomly
        send(command(orderId, "Springfield", "corr-ful-retry"));
        Fulfillment failed = awaitStatus(orderId, FulfillmentStatus.FAILED);
        awaitEvent(orderId, EventTypes.FULFILLMENT_FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);

        props.setFailureRate(0.0); // the underlying problem is fixed
        mvc.perform(post("/api/v1/admin/fulfillments/" + failed.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.operator()))
                .andExpect(status().isOk());

        Fulfillment done = awaitStatus(orderId, FulfillmentStatus.COMPLETED);
        EventEnvelope<JsonNode> completed = awaitEvent(orderId, EventTypes.FULFILLMENT_COMPLETED);
        assertThat(completed.payload().get("trackingNumber").asText()).isEqualTo(TrackingNumbers.forOrder(orderId));
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(statuses(orderId)).contains(FulfillmentStatus.FAILED).endsWith(FulfillmentStatus.PENDING,
                FulfillmentStatus.PICKING, FulfillmentStatus.PACKED, FulfillmentStatus.SHIPPED,
                FulfillmentStatus.COMPLETED);
    }

    @Test
    void inFlightFulfillmentResumesAfterRestart() {
        // Simulate a crash: the process stopped while the fulfillment was PICKING, with no schedule left.
        props.setSchedulerEnabled(false);
        UUID orderId = UUID.randomUUID();
        Fulfillment f = new Fulfillment(UUID.randomUUID(), orderId, "1 Main", "Springfield", "1", "US", 1,
                "corr-ful-restart", UUID.randomUUID(), Instant.now());
        f.recordAttempt();
        f.transitionTo(FulfillmentStatus.PICKING, Instant.now());
        f.clearSchedule();
        repository.save(f);

        // "restart": the startup hook runs again
        recovery.onReady();

        assertThat(repository.findById(f.getId()).orElseThrow().getNextStepAt()).isNotNull();
        assertThat(audit.search("Fulfillment", f.getId().toString(),
                org.springframework.data.domain.PageRequest.of(0, 10)).content())
                .extracting(a -> a.action()).contains("RESUMED_AFTER_RESTART");

        props.setSchedulerEnabled(true); // scheduler resumes from the persisted state
        Fulfillment done = awaitStatus(orderId, FulfillmentStatus.COMPLETED);
        assertThat(done.getTrackingNumber()).isEqualTo(TrackingNumbers.forOrder(orderId));
        assertThat(statuses(orderId)).containsExactly(FulfillmentStatus.PENDING, FulfillmentStatus.PICKING,
                FulfillmentStatus.PACKED, FulfillmentStatus.SHIPPED, FulfillmentStatus.COMPLETED);
        EventEnvelope<JsonNode> completed = awaitEvent(orderId, EventTypes.FULFILLMENT_COMPLETED);
        assertThat(completed.correlationId()).isEqualTo("corr-ful-restart");
    }
}
