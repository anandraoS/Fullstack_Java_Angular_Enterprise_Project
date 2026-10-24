package com.enterprise.oms.common.messaging;

import com.enterprise.oms.common.testapp.TestApplication;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.idempotency.ProcessedEvent;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Consumer infrastructure end-to-end on embedded KRaft Kafka: idempotent processing, duplicate counting,
 * retry with backoff then DLT for a poison message, immediate DLT for malformed JSON, unknown types ignored,
 * correlation-id MDC and ops status (lag).
 */
@Import(ConsumerKafkaTest.Config.class)
@SpringBootTest(classes = TestApplication.class, properties = "spring.datasource.url=jdbc:h2:mem:consumerkafka;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@EmbeddedKafka(partitions = 3, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.INVENTORY_EVENTS, Topics.INVENTORY_EVENTS + ".DLT"})
class ConsumerKafkaTest {

    static final String CONSUMER = "test-consumer";

    static class RecordingListener {
        final List<UUID> handled = new CopyOnWriteArrayList<>();
        final Map<UUID, String> mdcSeen = new ConcurrentHashMap<>();
        final AtomicInteger poisonAttempts = new AtomicInteger();
        private final EventRouter router;

        RecordingListener(EventRouterFactory factory) {
            this.router = factory.router(CONSUMER)
                    .on(EventTypes.INVENTORY_RESERVED, EventPayloads.InventoryReserved.class, (env, p) -> {
                        handled.add(env.eventId());
                        mdcSeen.put(env.eventId(), String.valueOf(MDC.get("correlationId")));
                    })
                    .on(EventTypes.INVENTORY_RELEASED, EventPayloads.InventoryReleased.class, (env, p) -> {
                        poisonAttempts.incrementAndGet();
                        throw new IllegalStateException("downstream unavailable");
                    })
                    .build();
        }

        @KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
        void on(ConsumerRecord<String, String> record) {
            router.route(record);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        RecordingListener recordingListener(EventRouterFactory factory) {
            return new RecordingListener(factory);
        }
    }

    @Autowired RecordingListener listener;
    @Autowired MessagingMetrics metrics;
    @Autowired MessagingStatusService status;
    @Autowired ProcessedEventRepository processed;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> kafka;

    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void subscribeToDlt() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-it-" + UUID.randomUUID(), "true", broker);
        dltConsumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        dltConsumer.subscribe(List.of(Topics.INVENTORY_EVENTS + ".DLT"));
    }

    @AfterEach
    void closeConsumer() {
        dltConsumer.close();
    }

    private EventEnvelope<?> reserved(String correlationId) {
        UUID orderId = UUID.randomUUID();
        return EventEnvelope.forOrder(EventTypes.INVENTORY_RESERVED, orderId, "inventory-service", correlationId, null,
                new EventPayloads.InventoryReserved(orderId, UUID.randomUUID(),
                        List.of(new EventPayloads.QuantityItem(UUID.randomUUID(), 2)), java.time.Instant.now()));
    }

    private void send(String key, String json) throws Exception {
        kafka.send(new ProducerRecord<>(Topics.INVENTORY_EVENTS, key, json)).get();
    }

    private ConsumerRecord<String, String> awaitDlt(String needle) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            ConsumerRecords<String, String> recs = dltConsumer.poll(Duration.ofMillis(200));
            recs.forEach(r -> {
                if (r.value() != null && r.value().contains(needle)) {
                    found.add(r);
                }
            });
            return !found.isEmpty();
        });
        return found.get(0);
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    void processesOnceAndCountsDuplicatesWithCorrelationInMdc() throws Exception {
        var env = reserved("corr-consume-1");
        String json = EventJson.toJson(env);
        long dupBefore = metrics.duplicatesTotal();

        send(env.aggregateId(), json);
        send(env.aggregateId(), json); // redelivery of the same eventId

        await().atMost(Duration.ofSeconds(30)).until(() -> metrics.duplicatesTotal() == dupBefore + 1);
        assertThat(listener.handled).containsOnlyOnce(env.eventId());
        assertThat(listener.mdcSeen.get(env.eventId())).isEqualTo("corr-consume-1");
        assertThat(processed.existsById(new ProcessedEvent.Key(CONSUMER, env.eventId()))).isTrue();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void poisonMessageIsRetriedWithBackoffThenDeadLettered() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder(EventTypes.INVENTORY_RELEASED, orderId, "inventory-service", "corr-poison",
                null, new EventPayloads.InventoryReleased(orderId, UUID.randomUUID(), "CANCELLATION"));
        int attemptsBefore = listener.poisonAttempts.get();
        long retriesBefore = metrics.retriesTotal();
        long dltBefore = metrics.deadLetteredTotal();

        send(orderId.toString(), EventJson.toJson(env));

        ConsumerRecord<String, String> dlt = awaitDlt(env.eventId().toString());
        assertThat(listener.poisonAttempts.get() - attemptsBefore).isEqualTo(4); // 1 delivery + 3 retries
        assertThat(dlt.topic()).isEqualTo("oms.inventory.events.v1.DLT");
        assertThat(dlt.key()).isEqualTo(orderId.toString());
        assertThat(header(dlt, "x-exception-class")).isEqualTo("java.lang.IllegalStateException");
        assertThat(header(dlt, "x-exception-message")).isEqualTo("downstream unavailable");
        assertThat(header(dlt, "x-original-topic")).isEqualTo(Topics.INVENTORY_EVENTS);
        await().atMost(Duration.ofSeconds(10)).until(() -> metrics.deadLetteredTotal() == dltBefore + 1);
        assertThat(metrics.retriesTotal() - retriesBefore).isEqualTo(3);
        assertThat(processed.existsById(new ProcessedEvent.Key(CONSUMER, env.eventId()))).isFalse();
    }

    @Test
    void malformedJsonGoesStraightToDltWithoutRetriesAndDoesNotBlockThePartition() throws Exception {
        String key = UUID.randomUUID().toString();
        String garbage = "{this is : not json " + key;
        long retriesBefore = metrics.retriesTotal();
        long dltBefore = metrics.deadLetteredTotal();

        send(key, garbage);
        var next = reserved("corr-after-garbage");
        send(key, EventJson.toJson(next));

        ConsumerRecord<String, String> dlt = awaitDlt(key);
        assertThat(dlt.value()).isEqualTo(garbage);
        assertThat(header(dlt, "x-exception-class")).contains("MalformedEventException");
        await().atMost(Duration.ofSeconds(30)).until(() -> listener.handled.contains(next.eventId()));
        assertThat(metrics.retriesTotal()).isEqualTo(retriesBefore); // classified non-retryable
        assertThat(metrics.deadLetteredTotal()).isEqualTo(dltBefore + 1);
    }

    @Test
    void unknownEventTypeIsIgnoredAndCountedNotDeadLettered() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder("SomethingFromTheFuture", orderId, "x", "c", null, Map.of("a", 1));
        long ignoredBefore = metrics.ignoredTotal();
        long dltBefore = metrics.deadLetteredTotal();

        send(orderId.toString(), EventJson.toJson(env));

        await().atMost(Duration.ofSeconds(30)).until(() -> metrics.ignoredTotal() == ignoredBefore + 1);
        assertThat(metrics.deadLetteredTotal()).isEqualTo(dltBefore);
        assertThat(processed.existsById(new ProcessedEvent.Key(CONSUMER, env.eventId()))).isFalse();
    }

    @Test
    void opsStatusReportsLagFromAdminClient() throws Exception {
        var env = reserved("corr-lag");
        send(env.aggregateId(), EventJson.toJson(env));
        await().atMost(Duration.ofSeconds(30)).until(() -> listener.handled.contains(env.eventId()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            MessagingStatus s = status.status();
            assertThat(s.lagAvailable()).isTrue();
            assertThat(s.service()).isEqualTo("test-service");
            assertThat(s.consumerGroup()).isEqualTo("test-service");
            assertThat(s.dltTopics()).containsExactly("oms.inventory.events.v1.DLT");
            MessagingStatus.TopicStatus t = s.topics().get(0);
            assertThat(t.topic()).isEqualTo(Topics.INVENTORY_EVENTS);
            assertThat(t.partitions()).isEqualTo(3);
            assertThat(t.consumerLag()).isZero();
            assertThat(t.committedOffsets()).hasSize(3);
            assertThat(s.processedEvents()).isPositive();
            assertThat(s.lastProcessedAt()).isNotNull();
        });
    }
}
