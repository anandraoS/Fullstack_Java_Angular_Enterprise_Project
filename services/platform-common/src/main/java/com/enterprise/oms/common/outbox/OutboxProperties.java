package com.enterprise.oms.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code oms.outbox.*}. Set {@code oms.outbox.enabled=false} in services that never publish (notification). */
@ConfigurationProperties(prefix = "oms.outbox")
public class OutboxProperties {

    /** Master toggle: OutboxService + relay + producer template. */
    private boolean enabled = true;
    /** Run the scheduled relay in this instance (false = append only). */
    private boolean relayEnabled = true;
    /** Max rows published per poll. */
    private int batchSize = 50;
    /** Delay between polls (fixed delay). */
    private long pollIntervalMs = 500;
    /** How long to wait for the broker ack of one record. */
    private long sendTimeoutMs = 5000;
    /** Published rows older than this are deleted by the cleanup job. */
    private Duration retention = Duration.ofHours(1);
    private boolean cleanupEnabled = true;
    private long cleanupIntervalMs = 60_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRelayEnabled() { return relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public long getSendTimeoutMs() { return sendTimeoutMs; }
    public void setSendTimeoutMs(long sendTimeoutMs) { this.sendTimeoutMs = sendTimeoutMs; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public boolean isCleanupEnabled() { return cleanupEnabled; }
    public void setCleanupEnabled(boolean cleanupEnabled) { this.cleanupEnabled = cleanupEnabled; }
    public long getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
}
