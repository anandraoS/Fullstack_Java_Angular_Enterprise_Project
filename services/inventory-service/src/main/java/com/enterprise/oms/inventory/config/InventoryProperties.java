package com.enterprise.oms.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.inventory.*}. */
@ConfigurationProperties(prefix = "oms.inventory")
public class InventoryProperties {

    /** Default reservation TTL when the ReserveInventory command carries none (contract: 900). */
    private int reservationTtlSeconds = 900;
    /** Upper bound applied to a TTL requested by a command (guards against absurd values). */
    private int maxReservationTtlSeconds = 86_400;
    /** atomic (default) | pessimistic | optimistic. */
    private String reservationStrategy = "atomic";
    /** Run the expiry scheduler at all (disable in tests that drive it manually). */
    private boolean expirySchedulerEnabled = true;
    /** Fixed delay between expiry scans. */
    private long expirySchedulerIntervalMs = 30_000;
    /** Max reservations expired per scan (each in its own transaction). */
    private int expiryBatchSize = 100;
    /** Bounded retries of the optimistic (compare-and-set on version) strategy per item. */
    private int optimisticMaxRetries = 20;
    /** Bounded retries of the admin stock adjustment on optimistic-lock failure. */
    private int stockAdjustMaxRetries = 5;
    /** available &lt;= this value counts as low stock in GET /inventory?lowStock=true. */
    private int lowStockThreshold = 10;
    /** Consume Kafka at all (listeners auto-start); false in pure REST/DB tests. */
    private boolean listenersEnabled = true;
    private final Cache cache = new Cache();

    public static class Cache {
        /** Product-read cache TTL; bounds staleness when an eviction is missed (multi-instance in-memory). */
        private int ttlSeconds = 30;
        /** Max entries of the in-memory implementation. */
        private int maxEntries = 1000;

        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }

    public int getReservationTtlSeconds() { return reservationTtlSeconds; }
    public void setReservationTtlSeconds(int v) { this.reservationTtlSeconds = v; }
    public int getMaxReservationTtlSeconds() { return maxReservationTtlSeconds; }
    public void setMaxReservationTtlSeconds(int v) { this.maxReservationTtlSeconds = v; }
    public String getReservationStrategy() { return reservationStrategy; }
    public void setReservationStrategy(String v) { this.reservationStrategy = v; }
    public boolean isExpirySchedulerEnabled() { return expirySchedulerEnabled; }
    public void setExpirySchedulerEnabled(boolean v) { this.expirySchedulerEnabled = v; }
    public long getExpirySchedulerIntervalMs() { return expirySchedulerIntervalMs; }
    public void setExpirySchedulerIntervalMs(long v) { this.expirySchedulerIntervalMs = v; }
    public int getExpiryBatchSize() { return expiryBatchSize; }
    public void setExpiryBatchSize(int v) { this.expiryBatchSize = v; }
    public int getOptimisticMaxRetries() { return optimisticMaxRetries; }
    public void setOptimisticMaxRetries(int v) { this.optimisticMaxRetries = v; }
    public int getStockAdjustMaxRetries() { return stockAdjustMaxRetries; }
    public void setStockAdjustMaxRetries(int v) { this.stockAdjustMaxRetries = v; }
    public int getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(int v) { this.lowStockThreshold = v; }
    public boolean isListenersEnabled() { return listenersEnabled; }
    public void setListenersEnabled(boolean v) { this.listenersEnabled = v; }
    public Cache getCache() { return cache; }
}
