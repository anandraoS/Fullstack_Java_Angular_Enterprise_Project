package com.enterprise.oms.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for TTL expiry ({@code oms.inventory.expiry-scheduler-interval-ms}, default 30s). Safe on several
 * instances: {@link ReservationLifecycleService#expireDue()} uses guarded UPDATEs so each reservation expires once.
 */
@Component
@ConditionalOnProperty(prefix = "oms.inventory", name = "expiry-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationLifecycleService lifecycle;

    public ReservationExpiryJob(ReservationLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.expiry-scheduler-interval-ms:30000}",
            initialDelayString = "${oms.inventory.expiry-scheduler-interval-ms:30000}")
    void run() {
        try {
            int expired = lifecycle.expireDue();
            if (expired > 0) {
                log.info("Expired {} reservation(s)", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Reservation expiry scan failed (will retry): {}", e.toString());
        }
    }
}
