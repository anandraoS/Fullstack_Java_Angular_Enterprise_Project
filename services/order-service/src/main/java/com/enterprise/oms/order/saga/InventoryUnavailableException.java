package com.enterprise.oms.order.saga;

/** Transient inventory failure: retried by Resilience4j, counted by the circuit breaker, retried again by the saga. */
public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
