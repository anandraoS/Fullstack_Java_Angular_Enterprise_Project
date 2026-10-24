package com.enterprise.oms.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and publishes pending rows to Kafka (producer: acks=all, idempotent). Delivery is
 * at-least-once: a crash between the broker ack and the {@code published_at} update re-sends the row; consumers
 * de-duplicate on eventId. Rows are published in creation order and a failure stops the batch (preserves order,
 * and avoids hammering a broken broker); the next poll retries. Never throws out of the scheduled method.
 * Several instances may run concurrently (duplicates are tolerated by idempotent consumers).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final OutboxService outboxService;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;

    public OutboxRelay(OutboxEventRepository repository, OutboxService outboxService,
                       KafkaTemplate<String, String> kafka, TransactionTemplate tx, OutboxProperties props) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${oms.outbox.poll-interval-ms:500}", initialDelayString = "${oms.outbox.poll-interval-ms:500}")
    public void poll() {
        try {
            publishPending();
        } catch (RuntimeException e) {
            log.warn("Outbox relay cycle failed (will retry): {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${oms.outbox.cleanup-interval-ms:60000}", initialDelayString = "${oms.outbox.cleanup-interval-ms:60000}")
    public void cleanup() {
        if (!props.isCleanupEnabled()) {
            return;
        }
        try {
            Instant before = Instant.now().minus(props.getRetention());
            Integer n = tx.execute(s -> repository.deletePublishedBefore(before));
            if (n != null && n > 0) {
                log.debug("Outbox cleanup removed {} published rows", n);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox cleanup failed: {}", e.toString());
        }
    }

    /**
     * Publishes one batch.
     *
     * @return number of rows published and marked
     */
    public int publishPending() {
        List<OutboxEvent> batch = tx.execute(s -> repository.findPending(PageRequest.of(0, props.getBatchSize())));
        int published = 0;
        if (batch != null) {
            for (OutboxEvent event : batch) {
                try {
                    send(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Outbox publish failed for event {} ({}): {}", event.getId(), event.getEventType(),
                            e.toString());
                    recordFailure(event, e);
                    break;
                }
                markPublished(event);
                published++;
            }
        }
        outboxService.invalidatePendingCache();
        return published;
    }

    private void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), null, event.getEventKey(),
                event.getPayload());
        for (Map.Entry<String, String> h : outboxService.headersOf(event).entrySet()) {
            record.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).get(props.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private void markPublished(OutboxEvent event) {
        try {
            tx.executeWithoutResult(s -> repository.findById(event.getId())
                    .ifPresent(e -> e.markPublished(Instant.now())));
        } catch (RuntimeException e) {
            // row stays pending -> will be re-sent (at-least-once)
            log.warn("Could not mark outbox event {} published: {}", event.getId(), e.toString());
        }
    }

    private void recordFailure(OutboxEvent event, Exception cause) {
        try {
            tx.executeWithoutResult(s -> repository.findById(event.getId())
                    .ifPresent(e -> e.markFailed(cause.toString())));
        } catch (RuntimeException e) {
            log.debug("Could not record outbox failure: {}", e.toString());
        }
    }
}
