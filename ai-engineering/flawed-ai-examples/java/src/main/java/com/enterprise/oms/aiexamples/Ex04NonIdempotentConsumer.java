package com.enterprise.oms.aiexamples;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Example 04: a consumer that is not idempotent. Kafka (and the outbox relay) deliver at-least-once, so the same
 * eventId can arrive twice. The real implementation is
 * {@code com.enterprise.oms.common.idempotency.IdempotentEventProcessor} (processed_events unique row inserted in the
 * SAME transaction as the business effect). See docs/adr/0005 and docs/architecture/delivery-semantics.md.
 */
public final class Ex04NonIdempotentConsumer {

    private Ex04NonIdempotentConsumer() { }

    public record InventoryReleased(String eventId, String orderId, int quantity) { }

    /** Models the stock row: reserved quantity. */
    public static final class Stock {
        private int reserved;
        public Stock(int reserved) { this.reserved = reserved; }
        public synchronized void releaseReserved(int q) { reserved -= q; }
        public synchronized int reserved() { return reserved; }
    }

    /** Flawed: applies the effect on every delivery. */
    public static final class FlawedConsumer {
        private final Stock stock;
        public FlawedConsumer(Stock stock) { this.stock = stock; }

        public void onEvent(InventoryReleased e) {
            stock.releaseReserved(e.quantity());
        }
    }

    /**
     * Fixed: dedupe marker and effect commit or roll back together (one "transaction", modelled with a lock and an
     * undo of the marker when the effect throws).
     */
    public static final class IdempotentConsumer {
        private final Set<String> processedEvents = new HashSet<>();   // processed_events(consumer, event_id)
        private final Consumer<InventoryReleased> effect;

        public IdempotentConsumer(Consumer<InventoryReleased> effect) { this.effect = effect; }

        /** @return true if the effect ran, false if the event was a duplicate */
        public synchronized boolean onEvent(InventoryReleased e) {
            if (!processedEvents.add(e.eventId())) {
                return false;                                           // duplicate: counted, skipped
            }
            try {
                effect.accept(e);
                return true;
            } catch (RuntimeException ex) {
                processedEvents.remove(e.eventId());                    // rollback: marker must not survive a failed effect
                throw ex;
            }
        }
    }
}
