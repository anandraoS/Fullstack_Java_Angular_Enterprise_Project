package com.enterprise.oms.common.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code GET /api/v1/ops/messaging} (contract section 5.5). {@code consumerLag} and
 * {@code committedOffsets} are {@code null} when {@code lagAvailable} is false.
 */
public record MessagingStatus(
        String service,
        String consumerGroup,
        List<TopicStatus> topics,
        long processedEvents,
        long duplicatesIgnored,
        long retries,
        long deadLettered,
        long outboxPending,
        Instant lastProcessedAt,
        List<String> dltTopics,
        boolean lagAvailable) {

    /** @param committedOffsets partition -> committed offset (null if unavailable) */
    public record TopicStatus(String topic, Integer partitions, Long consumerLag, Map<Integer, Long> committedOffsets) {
    }
}
