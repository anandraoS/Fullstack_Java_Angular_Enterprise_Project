package com.enterprise.oms.notification.service;

import java.util.UUID;

/**
 * A fulfillment event arrived for an order whose OrderCreated (customer e-mail) has not been seen yet. Deliberately
 * RETRYABLE (not an IllegalArgumentException): the two topics are independent, so the container retries with backoff
 * and only dead-letters if the contact never shows up.
 */
public class ContactUnavailableException extends RuntimeException {

    public ContactUnavailableException(UUID orderId) {
        super("No customer contact known yet for order " + orderId);
    }
}
