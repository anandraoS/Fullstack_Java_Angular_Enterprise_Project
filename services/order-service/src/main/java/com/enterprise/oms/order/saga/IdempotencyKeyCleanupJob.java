package com.enterprise.oms.order.saga;

import com.enterprise.oms.order.config.IdempotencyProperties;
import com.enterprise.oms.order.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** Deletes Idempotency-Key records older than {@code oms.idempotency.retention} (default 24h). */
@Component
public class IdempotencyKeyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties props;
    private final TransactionTemplate tx;

    public IdempotencyKeyCleanupJob(IdempotencyKeyRepository repository, IdempotencyProperties props,
                                    PlatformTransactionManager tm) {
        this.repository = repository;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${oms.idempotency.cleanup-interval-ms:3600000}",
            initialDelayString = "${oms.idempotency.cleanup-interval-ms:3600000}")
    public void scheduledRun() {
        if (!props.isCleanupEnabled()) {
            return;
        }
        try {
            cleanup();
        } catch (RuntimeException e) {
            log.warn("Idempotency key cleanup failed: {}", e.toString());
        }
    }

    /** @return number of deleted records */
    public int cleanup() {
        Instant before = Instant.now().minus(props.getRetention());
        Integer n = tx.execute(s -> repository.deleteCreatedBefore(before));
        return n == null ? 0 : n;
    }
}
