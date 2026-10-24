package com.enterprise.oms.localstack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** Wraps a single-node embedded KRaft Kafka broker with all OMS topics pre-created. */
public class LocalKafkaBroker {

    private static final Logger log = LoggerFactory.getLogger(LocalKafkaBroker.class);

    private final int port;
    private EmbeddedKafkaKraftBroker broker;

    /** @param port fixed listener port; 0 = random free port (tests) */
    public LocalKafkaBroker(int port) {
        this.port = port;
    }

    public synchronized void start() {
        if (broker != null) {
            return;
        }
        EmbeddedKafkaKraftBroker b = new EmbeddedKafkaKraftBroker(1, TopicCatalog.PARTITIONS,
                TopicCatalog.TOPICS.toArray(new String[0]));
        b.kafkaPorts(port);
        if (port > 0) {
            // EmbeddedKafkaKraftBroker ignores kafkaPorts(); pin the client listener explicitly (TestKit names: EXTERNAL / CONTROLLER)
            b.brokerProperty("listeners", "EXTERNAL://localhost:" + port + ",CONTROLLER://localhost:0");
            b.brokerProperty("advertised.listeners", "EXTERNAL://localhost:" + port);
        }
        // single node: internal topics must not require more replicas than brokers
        b.brokerProperty("offsets.topic.replication.factor", (short) 1);
        b.brokerProperty("transaction.state.log.replication.factor", (short) 1);
        b.brokerProperty("transaction.state.log.min.isr", 1);
        b.afterPropertiesSet();
        broker = b;
        log.info("Embedded Kafka (KRaft) started, bootstrap servers: {}, topics: {}", b.getBrokersAsString(),
                TopicCatalog.TOPICS);
    }

    public synchronized void stop() {
        if (broker != null) {
            broker.destroy();
            broker = null;
            log.info("Embedded Kafka stopped");
        }
    }

    public String bootstrapServers() {
        if (broker == null) {
            throw new IllegalStateException("broker not started");
        }
        return broker.getBrokersAsString();
    }
}
