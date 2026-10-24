package com.enterprise.oms.testsupport;

import com.enterprise.oms.common.events.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Base class for Kafka integration tests: starts an embedded KRaft broker (3 partitions) with ALL OMS topics and
 * their DLTs and points {@code spring.kafka.bootstrap-servers} at it. Combine with {@code @SpringBootTest}:
 * <pre>{@code
 * @SpringBootTest
 * class MyIT extends AbstractKafkaIT {
 *     @Test void x() throws Exception {
 *         send(Topics.ORDER_EVENTS, "key", json);
 *         var rec = awaitRecord(Topics.ORDER_EVENTS, r -> r.value().contains("abc"));
 *     }
 * }
 * }</pre>
 * The broker is shared by every test of the class (and by classes with the same annotation configuration).
 */
@EmbeddedKafka(partitions = Topics.PARTITIONS, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.ORDER_EVENTS, Topics.ORDER_EVENTS + Topics.DLT_SUFFIX,
                Topics.INVENTORY_COMMANDS, Topics.INVENTORY_COMMANDS + Topics.DLT_SUFFIX,
                Topics.INVENTORY_EVENTS, Topics.INVENTORY_EVENTS + Topics.DLT_SUFFIX,
                Topics.FULFILLMENT_COMMANDS, Topics.FULFILLMENT_COMMANDS + Topics.DLT_SUFFIX,
                Topics.FULFILLMENT_EVENTS, Topics.FULFILLMENT_EVENTS + Topics.DLT_SUFFIX})
public abstract class AbstractKafkaIT {

    @Autowired
    protected EmbeddedKafkaBroker embeddedKafka;

    /** New consumer with a unique group, earliest offsets, subscribed to the topics. Close it when done. */
    protected Consumer<String, String> newConsumer(String... topics) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("it-" + UUID.randomUUID(), "true", embeddedKafka);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(topics));
        return consumer;
    }

    /** Synchronously produces one record (acks=all). */
    protected void send(String topic, String key, String value) throws Exception {
        Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka);
        try (Producer<String, String> producer = new DefaultKafkaProducerFactory<>(props, new StringSerializer(),
                new StringSerializer()).createProducer()) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    /** Polls a fresh consumer until a record matching the predicate arrives (default timeout 30s). */
    protected ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            return awaitRecord(consumer, match, Duration.ofSeconds(30));
        }
    }

    protected ConsumerRecord<String, String> awaitRecord(Consumer<String, String> consumer,
                                                          Predicate<ConsumerRecord<String, String>> match,
                                                          Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        Awaits.until(timeout, () -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
            records.forEach(r -> {
                if (match.test(r)) {
                    found.add(r);
                }
            });
            return !found.isEmpty();
        });
        return found.get(0);
    }
}
