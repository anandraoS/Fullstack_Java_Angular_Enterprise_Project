package com.enterprise.oms.common.outbox;

import com.enterprise.oms.common.testapp.TestApplication;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** End-to-end: append in a transaction, scheduled relay publishes to (embedded KRaft) Kafka and marks published_at. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "oms.outbox.relay-enabled=true",
        "oms.outbox.poll-interval-ms=100",
        "spring.datasource.url=jdbc:h2:mem:outboxkafka;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@EmbeddedKafka(partitions = 3, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.ORDER_EVENTS, Topics.INVENTORY_COMMANDS, Topics.INVENTORY_EVENTS, Topics.FULFILLMENT_COMMANDS,
                Topics.FULFILLMENT_EVENTS})
class OutboxRelayKafkaTest {

    @Autowired OutboxService outbox;
    @Autowired OutboxEventRepository repository;
    @Autowired TransactionTemplate tx;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired MeterRegistry registry;

    private Consumer<String, String> consumer;

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("outbox-it-" + UUID.randomUUID(), "true", broker);
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of(Topics.ORDER_EVENTS));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    private ConsumerRecord<String, String> awaitRecord(String eventId) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(200));
            recs.forEach(r -> {
                if (r.value().contains(eventId)) {
                    found.add(r);
                }
            });
            return !found.isEmpty();
        });
        return found.get(0);
    }

    @Test
    void appendedEventIsPublishedWithKeyHeadersAndMarkedPublished() {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder(EventTypes.ORDER_CONFIRMED, orderId, "order-service", "corr-outbox",
                null, new EventPayloads.OrderConfirmed(orderId, UUID.randomUUID(), "a@example.test",
                        new java.math.BigDecimal("10.00")));

        tx.executeWithoutResult(s -> outbox.append(env));

        ConsumerRecord<String, String> rec = awaitRecord(env.eventId().toString());
        assertThat(rec.topic()).isEqualTo(Topics.ORDER_EVENTS);
        assertThat(rec.key()).isEqualTo(orderId.toString());
        assertThat(header(rec, "correlationId")).isEqualTo("corr-outbox");
        assertThat(header(rec, "eventType")).isEqualTo("OrderConfirmed");
        assertThat(header(rec, "schemaVersion")).isEqualTo("1");
        assertThat(header(rec, "eventId")).isEqualTo(env.eventId().toString());
        EventEnvelope<JsonNode> back = EventJson.parseEnvelope(rec.value());
        assertThat(back.eventId()).isEqualTo(env.eventId());
        assertThat(back.aggregateId()).isEqualTo(orderId.toString());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent row = repository.findById(env.eventId()).orElseThrow();
            assertThat(row.getPublishedAt()).isNotNull();
            assertThat(row.getAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(outbox.pendingCount()).isZero();
            assertThat(registry.get("oms.outbox.pending").gauge().value()).isZero();
        });
    }

    @Test
    void appendRequiresAnActiveTransaction() {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder(EventTypes.ORDER_FAILED, orderId, "order-service", "c", null,
                new EventPayloads.OrderFailed(orderId, UUID.randomUUID(), "a@example.test", "x"));
        assertThatThrownBy(() -> outbox.append(env)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRow() {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder(EventTypes.ORDER_FAILED, orderId, "order-service", "c", null,
                new EventPayloads.OrderFailed(orderId, UUID.randomUUID(), "a@example.test", "x"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.append(env);
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(env.eventId())).isEmpty();
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
