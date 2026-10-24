package com.enterprise.oms.order.saga;

/** Non-transient inventory failure (4xx): never retried inside the call, ignored by the circuit breaker. */
public class InventoryCallException extends RuntimeException {
    public InventoryCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
