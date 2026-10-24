package com.enterprise.oms.notification.sender;

import com.enterprise.oms.notification.domain.NotificationChannel;

import java.util.UUID;

/**
 * What the sender needs for one attempt.
 *
 * @param attemptNumber 1-based number of THIS attempt within the current delivery round
 */
public record NotificationMessage(UUID notificationId, UUID orderId, UUID customerId, NotificationChannel channel,
                                  String template, String subject, String recipient, int attemptNumber) {
}
