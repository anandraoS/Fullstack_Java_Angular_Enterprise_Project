package com.enterprise.oms.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.notification.*}. Mutable and volatile so tests can change simulation settings at runtime. */
@ConfigurationProperties(prefix = "oms.notification")
public class NotificationProperties {

    /** Delivery attempts before a notification is FAILED. */
    private volatile int maxAttempts = 5;
    /** Recipients at fail.example.test fail this many first attempts, then succeed. */
    private volatile int simulatedFailures = 2;
    private volatile long backoffInitialMs = 1000;
    private volatile double backoffMultiplier = 2.0;
    private volatile long backoffMaxMs = 30_000;
    private long pollIntervalMs = 250;
    private int batchSize = 25;
    private boolean schedulerEnabled = true;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getSimulatedFailures() { return simulatedFailures; }
    public void setSimulatedFailures(int simulatedFailures) { this.simulatedFailures = simulatedFailures; }
    public long getBackoffInitialMs() { return backoffInitialMs; }
    public void setBackoffInitialMs(long backoffInitialMs) { this.backoffInitialMs = backoffInitialMs; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    public long getBackoffMaxMs() { return backoffMaxMs; }
    public void setBackoffMaxMs(long backoffMaxMs) { this.backoffMaxMs = backoffMaxMs; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
}
