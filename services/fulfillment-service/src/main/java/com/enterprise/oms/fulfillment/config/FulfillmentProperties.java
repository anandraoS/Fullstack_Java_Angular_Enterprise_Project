package com.enterprise.oms.fulfillment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.fulfillment.*}. Mutable on purpose so tests can flip {@code failureRate} at runtime. */
@ConfigurationProperties(prefix = "oms.fulfillment")
public class FulfillmentProperties {

    /** Delay between lifecycle steps and between internal attempts (ms). */
    private volatile long stepDelayMs = 1500;
    /** Probability (0.0..1.0) that an attempt fails randomly. */
    private volatile double failureRate = 0.0;
    /** Internal attempts before FulfillmentFailed is published. */
    private volatile int maxAttempts = 3;
    private long pollIntervalMs = 250;
    private int batchSize = 25;
    private boolean schedulerEnabled = true;

    public long getStepDelayMs() { return stepDelayMs; }
    public void setStepDelayMs(long stepDelayMs) { this.stepDelayMs = stepDelayMs; }
    public double getFailureRate() { return failureRate; }
    public void setFailureRate(double failureRate) { this.failureRate = failureRate; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
}
