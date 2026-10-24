package com.enterprise.oms.notification;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.testsupport.AbstractPostgresIT;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Same Flyway migrations and JPA mappings against a real PostgreSQL 16 (Testcontainers), including the
 * unique(source_event_id, template) constraint. Skipped, not failed, without Docker.
 */
@SpringBootTest(properties = {"oms.notification.scheduler-enabled=true"})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.ORDER_EVENTS, Topics.ORDER_EVENTS + ".DLT", Topics.FULFILLMENT_EVENTS,
                Topics.FULFILLMENT_EVENTS + ".DLT"})
class NotificationPostgresIT extends AbstractPostgresIT {

    @Autowired NotificationRepository repository;
    @Autowired @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> kafka;

    @Test
    void orderCreatedIsDeliveredAndDuplicateEventIsIgnoredOnPostgres() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = EventEnvelope.forOrder(EventTypes.ORDER_CREATED, orderId, "order-service", "corr-pg", null,
                new EventPayloads.OrderCreated(orderId, UUID.randomUUID(), "buyer@acme.example.test",
                        new BigDecimal("1.00"), "USD", List.of(), "Springfield"));
        String json = EventJson.toJson(env);
        kafka.send(new ProducerRecord<>(Topics.ORDER_EVENTS, orderId.toString(), json)).get();
        kafka.send(new ProducerRecord<>(Topics.ORDER_EVENTS, orderId.toString(), json)).get();

        await().atMost(Duration.ofSeconds(40)).until(() -> repository.findAll().stream()
                .anyMatch(n -> n.getOrderId().equals(orderId) && n.getStatus() == NotificationStatus.SENT));
        assertThat(repository.findAll().stream().filter(n -> n.getOrderId().equals(orderId))).hasSize(1);
    }
}
