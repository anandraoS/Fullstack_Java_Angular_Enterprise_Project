package com.enterprise.oms.common.idempotency;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Effectively-once business effect on top of at-least-once delivery: inserts
 * {@code processed_events(consumer, event_id)} in the SAME transaction as the business effect. A second delivery of
 * the same eventId finds the row and the effect is skipped (counted in {@code oms.events.duplicates}).
 * <p>
 * Race note: two concurrent deliveries of one event both pass the existence check; the loser fails on the primary
 * key and its transaction rolls back (so its effect is NOT applied); the container retries it and then sees the
 * duplicate. No effect is ever applied twice.
 */
public class IdempotentEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventProcessor.class);

    private final ProcessedEventRepository repository;
    private final MessagingMetrics metrics;

    public IdempotentEventProcessor(ProcessedEventRepository repository, MessagingMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /**
     * @param consumer logical consumer name (normally the service name / group id)
     * @param effect   business effect; runs inside the transaction opened here (or the caller's)
     * @return {@code true} if the effect ran, {@code false} if the event was a duplicate
     */
    @Transactional
    public boolean processOnce(String consumer, EventEnvelope<?> envelope, Runnable effect) {
        return processOnce(consumer, null, envelope, effect);
    }

    @Transactional
    public boolean processOnce(String consumer, String topic, EventEnvelope<?> envelope, Runnable effect) {
        ProcessedEvent.Key key = new ProcessedEvent.Key(consumer, envelope.eventId());
        if (repository.existsById(key)) {
            log.info("Duplicate event ignored: consumer={} eventId={} type={}", consumer, envelope.eventId(),
                    envelope.eventType());
            metrics.duplicate(topic, envelope.eventType());
            return false;
        }
        repository.saveAndFlush(new ProcessedEvent(consumer, envelope.eventId(), envelope.eventType(), topic,
                Instant.now()));
        effect.run();
        metrics.processed(topic, envelope.eventType());
        return true;
    }
}
