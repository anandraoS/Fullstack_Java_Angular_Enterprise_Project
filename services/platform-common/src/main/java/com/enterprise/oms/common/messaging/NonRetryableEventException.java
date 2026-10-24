package com.enterprise.oms.common.messaging;

/**
 * Throw from an event handler when retrying cannot help (validation failure, impossible state). The error handler
 * skips the retries and publishes the record to {@code <topic>.DLT} immediately.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
