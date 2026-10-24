package com.enterprise.oms.testsupport;

import com.enterprise.oms.common.events.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = KafkaSupportTest.Cfg.class)
class KafkaSupportTest extends AbstractKafkaIT {

    @Configuration
    static class Cfg {
    }

    @Test
    void embeddedBrokerHasAllTopicsAndDltsAndRoundTrips() throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", embeddedKafka.getBrokersAsString()))) {
            Set<String> names = admin.listTopics().names().get();
            assertThat(names).containsAll(Topics.ALL_WITH_DLT);
            assertThat(admin.describeTopics(Set.of(Topics.ORDER_EVENTS)).allTopicNames().get()
                    .get(Topics.ORDER_EVENTS).partitions()).hasSize(3);
        }
        String id = UUID.randomUUID().toString();
        send(Topics.ORDER_EVENTS, "k-" + id, "{\"id\":\"" + id + "\"}");
        ConsumerRecord<String, String> rec = awaitRecord(Topics.ORDER_EVENTS, r -> r.value().contains(id));
        assertThat(rec.key()).isEqualTo("k-" + id);
    }
}
