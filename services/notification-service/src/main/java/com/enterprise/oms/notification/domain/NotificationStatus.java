package com.enterprise.oms.notification.domain;

import com.enterprise.oms.common.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Delivery status of a notification with its pure transition rules:
 * PENDING -> SENT | RETRYING | FAILED, RETRYING -> SENT | RETRYING | FAILED, FAILED -> PENDING (operator retry),
 * SENT is terminal.
 */
public enum NotificationStatus {
    PENDING, SENT, FAILED, RETRYING;

    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED = new EnumMap<>(NotificationStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(SENT, RETRYING, FAILED));
        ALLOWED.put(RETRYING, EnumSet.of(SENT, RETRYING, FAILED));
        ALLOWED.put(FAILED, EnumSet.of(PENDING));
        ALLOWED.put(SENT, EnumSet.noneOf(NotificationStatus.class));
    }

    public boolean canTransitionTo(NotificationStatus to) {
        return ALLOWED.get(this).contains(to);
    }

    public void assertTransitionTo(NotificationStatus to) {
        if (!canTransitionTo(to)) {
            throw new InvalidStateTransitionException("Notification", this, to);
        }
    }

    /** True while the delivery scheduler still has work to do for this status. */
    public boolean isDeliverable() {
        return this == PENDING || this == RETRYING;
    }
}
