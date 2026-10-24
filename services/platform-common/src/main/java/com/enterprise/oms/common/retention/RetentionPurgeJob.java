package com.enterprise.oms.common.retention;

import com.enterprise.oms.common.audit.AuditEventRepository;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Deletes {@code processed_events} and {@code audit_events} rows older than the configured retention. Safe to run on
 * every replica concurrently: a delete by timestamp is idempotent, so the worst case is redundant work. Never throws
 * out of the scheduled method; each table is purged in its own transaction so one failure does not block the other.
 */
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final ProcessedEventRepository processedEvents;
    private final AuditEventRepository auditEvents;
    private final TransactionTemplate tx;
    private final RetentionProperties props;
    private final Clock clock;

    public RetentionPurgeJob(ProcessedEventRepository processedEvents, AuditEventRepository auditEvents,
                             TransactionTemplate tx, RetentionProperties props, Clock clock) {
        this.processedEvents = processedEvents;
        this.auditEvents = auditEvents;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oms.retention.interval-ms:3600000}", initialDelayString = "${oms.retention.interval-ms:3600000}")
    public void purge() {
        if (!props.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        try {
            Integer n = tx.execute(s -> processedEvents.deleteProcessedBefore(now.minus(props.getProcessedEventsRetention())));
            if (n != null && n > 0) {
                log.info("Retention purge removed {} processed_events rows", n);
            }
        } catch (RuntimeException e) {
            log.warn("processed_events purge failed (will retry): {}", e.toString());
        }
        try {
            Integer n = tx.execute(s -> auditEvents.deleteOccurredBefore(now.minus(props.getAuditEventsRetention())));
            if (n != null && n > 0) {
                log.info("Retention purge removed {} audit_events rows", n);
            }
        } catch (RuntimeException e) {
            log.warn("audit_events purge failed (will retry): {}", e.toString());
        }
    }
}
