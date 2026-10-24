package com.enterprise.oms.inventory.reservation;

/**
 * The optimistic strategy lost its compare-and-set more often than the configured bound. Thrown (never swallowed) so
 * the enclosing transaction rolls back every hold; the Kafka container then retries the command with backoff.
 */
public class ReservationContentionException extends RuntimeException {

    public ReservationContentionException(String message) {
        super(message);
    }
}
