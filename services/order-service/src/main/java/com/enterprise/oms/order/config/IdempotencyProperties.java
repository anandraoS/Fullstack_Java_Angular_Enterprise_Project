package com.enterprise.oms.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code oms.idempotency.*}: how long Idempotency-Key records are kept. */
@ConfigurationProperties(prefix = "oms.idempotency")
public class IdempotencyProperties {

    private Duration retention = Duration.ofHours(24);
    private boolean cleanupEnabled = true;
    private long cleanupIntervalMs = 3_600_000;

    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public boolean isCleanupEnabled() { return cleanupEnabled; }
    public void setCleanupEnabled(boolean cleanupEnabled) { this.cleanupEnabled = cleanupEnabled; }
    public long getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
}
