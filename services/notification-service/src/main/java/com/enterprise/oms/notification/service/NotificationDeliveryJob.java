package com.enterprise.oms.notification.service;

import com.enterprise.oms.notification.config.NotificationProperties;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Retry/backoff scheduler: every poll it makes one delivery attempt for each due PENDING/RETRYING notification.
 * All state (attempts, next_attempt_at) is in the database, so a restart simply resumes; safe with several instances
 * (optimistic locking on {@code version}).
 */
@Component
public class NotificationDeliveryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryJob.class);

    private final NotificationRepository repository;
    private final NotificationService service;
    private final NotificationProperties props;
    private final Clock clock;

    public NotificationDeliveryJob(NotificationRepository repository, NotificationService service,
                                   NotificationProperties props, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oms.notification.poll-interval-ms:250}",
            initialDelayString = "${oms.notification.poll-interval-ms:250}")
    public void poll() {
        if (!props.isSchedulerEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Notification delivery poll failed: {}", e.toString());
        }
    }

    /** One scheduling pass (also used by tests). @return number of delivery attempts made */
    public int runOnce() {
        List<UUID> due = repository.findDueIds(EnumSet.of(NotificationStatus.PENDING, NotificationStatus.RETRYING),
                clock.instant(), PageRequest.of(0, props.getBatchSize()));
        int attempted = 0;
        for (UUID id : due) {
            try {
                if (service.deliver(id)) {
                    attempted++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("Notification {} handled concurrently, skipping", id);
            } catch (RuntimeException e) {
                log.warn("Delivering notification {} failed: {}", id, e.toString());
            }
        }
        return attempted;
    }
}
