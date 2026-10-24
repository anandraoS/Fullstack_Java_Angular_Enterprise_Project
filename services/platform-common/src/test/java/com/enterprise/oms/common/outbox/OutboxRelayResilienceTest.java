package com.enterprise.oms.common.outbox;

import com.enterprise.oms.common.testapp.TestApplication;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The relay never throws when Kafka is down, keeps rows pending, preserves order and delivers after recovery. */
@SpringBootTest(classes = TestApplication.class, properties =
        "spring.datasource.url=jdbc:h2:mem:outboxresilience;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OutboxRelayResilienceTest {

    @Autowired OutboxService outbox;
    @Autowired OutboxEventRepository repository;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxProperties props;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private EventEnvelope<EventPayloads.OrderFailed> append() {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder(EventTypes.ORDER_FAILED, orderId, "order-service", "c-" + orderId, null,
                new EventPayloads.OrderFailed(orderId, UUID.randomUUID(), "a@example.test", "r"));
        tx.executeWithoutResult(s -> outbox.append(env));
        return env;
    }

    @SuppressWarnings("unchecked")
    @Test
    void kafkaDownThenRecoveredRetriesInOrderAndMarksPublished() {
        var first = append();
        var second = append();

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = CompletableFuture.failedFuture(
                new TimeoutException("broker unavailable"));
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed).thenReturn(ok);
        OutboxRelay relay = new OutboxRelay(repository, outbox, kafka, tx, props);

        // cycle 1: Kafka down -> nothing published, no exception, rows stay pending with error recorded
        assertThat(relay.publishPending()).isZero();
        OutboxEvent a = repository.findById(first.eventId()).orElseThrow();
        assertThat(a.getPublishedAt()).isNull();
        assertThat(a.getAttempts()).isEqualTo(1);
        assertThat(a.getLastError()).contains("broker unavailable");
        assertThat(outbox.pendingCount()).isEqualTo(2);

        // cycle 2: Kafka back -> both published, oldest first
        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(repository.findById(first.eventId()).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(repository.findById(second.eventId()).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(repository.findById(first.eventId()).orElseThrow().getLastError()).isNull();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, org.mockito.Mockito.times(3)).send(sent.capture());
        List<ProducerRecord<String, String>> records = sent.getAllValues();
        assertThat(records.get(0).value()).contains(first.eventId().toString());
        assertThat(records.get(1).value()).contains(first.eventId().toString());
        assertThat(records.get(2).value()).contains(second.eventId().toString());
        assertThat(relay.publishPending()).isZero();
        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    void unreachableBrokerDoesNotThrowAndKeepsRowPending() {
        var env = append();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 300);
        cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 300);
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 600);
        KafkaTemplate<String, String> deadKafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
        OutboxProperties quick = new OutboxProperties();
        quick.setSendTimeoutMs(1000);
        OutboxRelay relay = new OutboxRelay(repository, outbox, deadKafka, tx, quick);

        assertThat(relay.publishPending()).isZero();
        relay.poll(); // the scheduled entry point must swallow failures too

        OutboxEvent row = repository.findById(env.eventId()).orElseThrow();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isGreaterThanOrEqualTo(2);
        assertThat(row.getLastError()).isNotBlank();
        deadKafka.destroy();
    }

    @Test
    void batchSizeLimitsRowsPerCycle() {
        for (int i = 0; i < 3; i++) {
            append();
        }
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxProperties small = new OutboxProperties();
        small.setBatchSize(2);
        OutboxRelay relay = new OutboxRelay(repository, outbox, kafka, tx, small);
        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(new BigDecimal(outbox.pendingCount())).isZero();
    }
}
