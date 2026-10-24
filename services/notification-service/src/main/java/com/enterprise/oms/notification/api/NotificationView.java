package com.enterprise.oms.notification.api;

import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationChannel;
import com.enterprise.oms.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/** Contract 5.4 Notification JSON. The recipient address is intentionally NOT part of it. */
public record NotificationView(UUID id, UUID customerId, UUID orderId, NotificationChannel channel, String template,
                               String subject, NotificationStatus status, int attempts, String lastError,
                               String correlationId, UUID sourceEventId, Instant createdAt, Instant sentAt) {

    public static NotificationView of(Notification n) {
        return new NotificationView(n.getId(), n.getCustomerId(), n.getOrderId(), n.getChannel(), n.getTemplate(),
                n.getSubject(), n.getStatus(), n.getAttempts(),
                n.getLastError() == null ? null : LogMasking.sanitize(n.getLastError()), n.getCorrelationId(),
                n.getSourceEventId(), n.getCreatedAt(), n.getSentAt());
    }
}
