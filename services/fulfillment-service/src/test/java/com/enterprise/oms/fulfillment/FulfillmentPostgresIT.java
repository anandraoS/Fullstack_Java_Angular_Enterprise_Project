package com.enterprise.oms.fulfillment;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import com.enterprise.oms.testsupport.AbstractPostgresIT;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Same Flyway migrations and JPA mappings against a real PostgreSQL 16 (Testcontainers). Skipped, not failed,
 * when Docker is unavailable ({@code disabledWithoutDocker = true} on the base class).
 */
@SpringBootTest(properties = {
        "oms.outbox.relay-enabled=true",
        "oms.fulfillment.scheduler-enabled=true",
        "oms.fulfillment.step-delay-ms=20",
        "oms.fulfillment.poll-interval-ms=20"})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.FULFILLMENT_COMMANDS, Topics.FULFILLMENT_COMMANDS + ".DLT", Topics.FULFILLMENT_EVENTS})
class FulfillmentPostgresIT extends AbstractPostgresIT {

    @Autowired FulfillmentRepository repository;
    @Autowired @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> kafka;

    @Test
    void commandRunsToCompletionOnPostgres() throws Exception {
        UUID orderId = UUID.randomUUID();
        var cmd = EventEnvelope.forOrder(EventTypes.START_FULFILLMENT, orderId, "order-service", "corr-pg", null,
                new EventPayloads.StartFulfillment(orderId, new EventPayloads.Address("1 Main", "Springfield", "1", "US"),
                        List.of(new EventPayloads.SkuItem(UUID.randomUUID(), "SKU-1001", 1))));
        kafka.send(new ProducerRecord<>(Topics.FULFILLMENT_COMMANDS, orderId.toString(), EventJson.toJson(cmd))).get();

        await().atMost(Duration.ofSeconds(40)).until(() ->
                repository.findByOrderId(orderId).map(f -> f.getStatus() == FulfillmentStatus.COMPLETED).orElse(false));
        assertThat(repository.findByOrderId(orderId).orElseThrow().getTrackingNumber()).startsWith("OMS-");
        assertThat(repository.existsByOrderId(orderId)).isTrue();
    }
}
