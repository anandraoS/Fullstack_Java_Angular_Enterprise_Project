package com.enterprise.oms.notification.sender;

/**
 * Delivery port. The only implementation shipped is {@link SimulatedSender}; a real provider (SES, SendGrid, ...) would
 * implement this interface. Implementations must not log the recipient unmasked.
 */
public interface NotificationSender {

    SendResult send(NotificationMessage message);
}
