package com.enterprise.oms.common.events;

import com.enterprise.oms.common.messaging.NonRetryableEventException;

/** The record value is not a valid envelope/payload. Non-retryable: goes straight to the DLT. */
public class MalformedEventException extends NonRetryableEventException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
