package com.enterprise.oms.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.reconciliation.*} */
@ConfigurationProperties(prefix = "oms.reconciliation")
public class ReconciliationProperties {

    private boolean enabled = true;
    private long intervalMs = 300_000;
    /** Only inconsistencies older than this are touched (in-flight work looks inconsistent for a moment). */
    private long minAgeSeconds = 120;
    private int batchSize = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public long getMinAgeSeconds() { return minAgeSeconds; }
    public void setMinAgeSeconds(long minAgeSeconds) { this.minAgeSeconds = minAgeSeconds; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
