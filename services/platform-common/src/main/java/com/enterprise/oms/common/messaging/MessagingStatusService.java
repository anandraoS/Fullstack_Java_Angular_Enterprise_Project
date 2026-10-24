package com.enterprise.oms.common.messaging;

import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.outbox.OutboxService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Builds {@link MessagingStatus}. Lag comes from a short-lived Kafka {@link Admin} client; any failure (broker down,
 * timeout, missing topic) yields {@code lagAvailable=false} with null lag. It never throws.
 */
public class MessagingStatusService {

    private static final Logger log = LoggerFactory.getLogger(MessagingStatusService.class);

    private final String serviceName;
    private final MessagingProperties props;
    private final KafkaProperties kafkaProperties;
    private final MessagingMetrics metrics;
    private final ObjectProvider<OutboxService> outbox;

    public MessagingStatusService(String serviceName, MessagingProperties props, KafkaProperties kafkaProperties,
                                  MessagingMetrics metrics, ObjectProvider<OutboxService> outbox) {
        this.serviceName = serviceName;
        this.props = props;
        this.kafkaProperties = kafkaProperties;
        this.metrics = metrics;
        this.outbox = outbox;
    }

    public String consumerGroup() {
        return props.getConsumerGroup() != null ? props.getConsumerGroup() : serviceName;
    }

    public MessagingStatus status() {
        List<String> topics = props.getTopics().isEmpty() ? Topics.ALL : props.getTopics();
        List<MessagingStatus.TopicStatus> topicStatuses = new ArrayList<>();
        boolean lagAvailable = false;
        try {
            topicStatuses = lagFor(topics);
            lagAvailable = true;
        } catch (Exception e) {
            log.debug("Kafka lag unavailable: {}", e.toString());
            for (String t : topics) {
                topicStatuses.add(new MessagingStatus.TopicStatus(t, null, null, null));
            }
        }
        OutboxService ob = outbox.getIfAvailable();
        long pending = 0;
        if (ob != null) {
            try {
                pending = ob.pendingCount();
            } catch (RuntimeException ignored) {
                // leave 0
            }
        }
        return new MessagingStatus(serviceName, consumerGroup(), topicStatuses, metrics.processedTotal(),
                metrics.duplicatesTotal(), metrics.retriesTotal(), metrics.deadLetteredTotal(), pending,
                metrics.lastProcessedAt(), topics.stream().map(Topics::dlt).toList(), lagAvailable);
    }

    private List<MessagingStatus.TopicStatus> lagFor(List<String> topics) throws Exception {
        long timeout = props.getAdminTimeoutMs();
        Map<String, Object> cfg = new HashMap<>(kafkaProperties.buildAdminProperties(null));
        cfg.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout);
        cfg.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout);
        cfg.put(AdminClientConfig.CLIENT_ID_CONFIG, serviceName + "-ops-admin");
        try (Admin admin = Admin.create(cfg)) {
            Map<String, TopicDescription> descriptions = admin.describeTopics(topics).allTopicNames()
                    .get(timeout, TimeUnit.MILLISECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(consumerGroup())
                    .partitionsToOffsetAndMetadata().get(timeout, TimeUnit.MILLISECONDS);
            Map<TopicPartition, OffsetSpec> latestReq = new HashMap<>();
            descriptions.forEach((t, d) -> d.partitions()
                    .forEach(p -> latestReq.put(new TopicPartition(t, p.partition()), OffsetSpec.latest())));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = admin.listOffsets(latestReq).all()
                    .get(timeout, TimeUnit.MILLISECONDS);

            List<MessagingStatus.TopicStatus> out = new ArrayList<>();
            for (String t : topics) {
                TopicDescription d = descriptions.get(t);
                Map<Integer, Long> offsets = new TreeMap<>();
                long lag = 0;
                for (var p : d.partitions()) {
                    TopicPartition tp = new TopicPartition(t, p.partition());
                    OffsetAndMetadata c = committed.get(tp);
                    long end = latest.get(tp).offset();
                    long committedOffset = c == null ? 0L : c.offset();
                    offsets.put(p.partition(), committedOffset);
                    lag += Math.max(0, end - committedOffset);
                }
                out.add(new MessagingStatus.TopicStatus(t, d.partitions().size(), lag, offsets));
            }
            return out;
        }
    }
}
