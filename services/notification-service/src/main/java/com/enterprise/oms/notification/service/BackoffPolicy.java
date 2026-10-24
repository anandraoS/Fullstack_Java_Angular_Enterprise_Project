package com.enterprise.oms.notification.service;

import com.enterprise.oms.notification.config.NotificationProperties;
import org.springframework.stereotype.Component;

/** Exponential backoff: {@code min(max, initial * multiplier^(attempt-1))} milliseconds after failed attempt n. */
@Component
public class BackoffPolicy {

    private final NotificationProperties props;

    public BackoffPolicy(NotificationProperties props) {
        this.props = props;
    }

    /** @param failedAttempt number of the attempt that just failed (1-based) */
    public long delayMs(int failedAttempt) {
        if (failedAttempt < 1) {
            return 0;
        }
        double delay = props.getBackoffInitialMs() * Math.pow(props.getBackoffMultiplier(), failedAttempt - 1);
        return (long) Math.min((double) props.getBackoffMaxMs(), delay);
    }
}
