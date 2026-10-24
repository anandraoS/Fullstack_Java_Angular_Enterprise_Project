package com.enterprise.oms.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload records of every event/command, field names exactly as in contract section 7.
 * Evolution rule: additive only (new optional fields); readers are tolerant (unknown fields ignored).
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /** Reasons carried by {@link ReleaseInventory} / {@link InventoryReleased}. */
    public static final class ReleaseReasons {
        public static final String CANCELLATION = "CANCELLATION";
        public static final String FULFILLMENT_FAILURE = "FULFILLMENT_FAILURE";
        public static final String EXPIRED = "EXPIRED";

        private ReleaseReasons() {
        }
    }

    // ---- shared value records -------------------------------------------------------------------------------

    public record Address(String line1, String city, String postalCode, String country) {
    }

    /** Line of {@link OrderCreated}. */
    public record PricedItem(UUID productId, String sku, int quantity, BigDecimal unitPrice) {
    }

    /** Line of {@link ReserveInventory} / {@link InventoryReserved}. */
    public record QuantityItem(UUID productId, int quantity) {
    }

    /** Line of {@link StartFulfillment}. */
    public record SkuItem(UUID productId, String sku, int quantity) {
    }

    public record Shortage(UUID productId, int requested, int available) {
    }

    // ---- order-service events (oms.order.events.v1) ---------------------------------------------------------

    public record OrderCreated(UUID orderId, UUID customerId, String customerEmail, BigDecimal totalAmount,
                               String currency, List<PricedItem> items, String shippingCity) {
    }

    public record OrderConfirmed(UUID orderId, UUID customerId, String customerEmail, BigDecimal totalAmount) {
    }

    public record OrderCancelled(UUID orderId, UUID customerId, String customerEmail, String reason) {
    }

    public record OrderRejected(UUID orderId, UUID customerId, String customerEmail, String reason) {
    }

    public record OrderFulfilled(UUID orderId, UUID customerId, String customerEmail, String trackingNumber) {
    }

    public record OrderFailed(UUID orderId, UUID customerId, String customerEmail, String reason) {
    }

    // ---- inventory commands (oms.inventory.commands.v1) -----------------------------------------------------

    public record ReserveInventory(UUID orderId, List<QuantityItem> items, int reservationTtlSeconds) {
    }

    /** {@code reason} is one of {@link ReleaseReasons}. */
    public record ReleaseInventory(UUID orderId, String reason) {
    }

    // ---- inventory events (oms.inventory.events.v1) ---------------------------------------------------------

    public record InventoryReserved(UUID orderId, UUID reservationId, List<QuantityItem> items, Instant expiresAt) {
    }

    public record InventoryReservationFailed(UUID orderId, String reason, List<Shortage> shortages) {
    }

    public record InventoryReleased(UUID orderId, UUID reservationId, String reason) {
    }

    // ---- fulfillment (commands + events) --------------------------------------------------------------------

    public record StartFulfillment(UUID orderId, Address shippingAddress, List<SkuItem> items) {
    }

    public record FulfillmentStarted(UUID orderId, UUID fulfillmentId) {
    }

    public record FulfillmentCompleted(UUID orderId, UUID fulfillmentId, String trackingNumber) {
    }

    public record FulfillmentFailed(UUID orderId, UUID fulfillmentId, String reason, int attempts) {
    }
}
