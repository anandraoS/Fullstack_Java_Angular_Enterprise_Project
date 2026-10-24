package com.enterprise.oms.localstack;

import com.enterprise.oms.common.events.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocalKafkaBrokerTest {

    private static LocalKafkaBroker broker;
    private static int port;

    @BeforeAll
    static void start() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort(); // a free port, so the test never clashes with a running local-stack on 19092
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        broker = new LocalKafkaBroker(port);
        broker.start();
    }

    @AfterAll
    static void stop() {
        broker.stop();
    }

    @Test
    void brokerListensOnTheRequestedFixedPort() {
        assertThat(broker.bootstrapServers()).isEqualTo("localhost:" + port);
    }

    @Test
    void topicCatalogMatchesPlatformCommonTopics() {
        assertThat(new HashSet<>(TopicCatalog.TOPICS)).isEqualTo(new HashSet<>(Topics.ALL_WITH_DLT));
        assertThat(TopicCatalog.PARTITIONS).isEqualTo(Topics.PARTITIONS);
    }

    @Test
    void allTopicsAndDltsExistWithThreePartitions() throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.bootstrapServers()))) {
            Set<String> names = admin.listTopics().names().get();
            assertThat(names).containsAll(TopicCatalog.TOPICS);
            Map<String, TopicDescription> described = admin.describeTopics(TopicCatalog.TOPICS).allTopicNames().get();
            described.values().forEach(d -> assertThat(d.partitions()).as(d.name()).hasSize(3));
        }
    }
}
