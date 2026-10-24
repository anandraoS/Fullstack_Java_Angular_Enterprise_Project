package com.enterprise.oms.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.saga.*}: orchestration, retry and recovery tuning. */
@ConfigurationProperties(prefix = "oms.saga")
public class SagaProperties {

    /** A saga with no progress for this long is re-driven by the SagaRecoveryJob (contract default 60). */
    private long stuckAfterSeconds = 60;
    /** Re-drive attempts per step before the saga gives up (FAILED for non-compensable steps, flagged otherwise). */
    private int maxAttempts = 5;
    /** Run the recovery job on a schedule (the method can always be called directly). */
    private boolean recoveryEnabled = true;
    private long recoveryIntervalMs = 10_000;
    private int recoveryBatchSize = 50;
    /** TTL asked from inventory for a reservation (inventory default 900). */
    private int reservationTtlSeconds = 900;
    /** Start validation asynchronously right after the order commits (tests drive it explicitly when false). */
    private boolean autoStart = true;
    /** Start the Kafka listeners (false in tests that must not consume). */
    private boolean listenersEnabled = true;
    private int executorCoreSize = 2;
    private int executorMaxSize = 4;
    private int executorQueueCapacity = 200;

    public long getStuckAfterSeconds() { return stuckAfterSeconds; }
    public void setStuckAfterSeconds(long stuckAfterSeconds) { this.stuckAfterSeconds = stuckAfterSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public boolean isRecoveryEnabled() { return recoveryEnabled; }
    public void setRecoveryEnabled(boolean recoveryEnabled) { this.recoveryEnabled = recoveryEnabled; }
    public long getRecoveryIntervalMs() { return recoveryIntervalMs; }
    public void setRecoveryIntervalMs(long recoveryIntervalMs) { this.recoveryIntervalMs = recoveryIntervalMs; }
    public int getRecoveryBatchSize() { return recoveryBatchSize; }
    public void setRecoveryBatchSize(int recoveryBatchSize) { this.recoveryBatchSize = recoveryBatchSize; }
    public int getReservationTtlSeconds() { return reservationTtlSeconds; }
    public void setReservationTtlSeconds(int reservationTtlSeconds) { this.reservationTtlSeconds = reservationTtlSeconds; }
    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
    public boolean isListenersEnabled() { return listenersEnabled; }
    public void setListenersEnabled(boolean listenersEnabled) { this.listenersEnabled = listenersEnabled; }
    public int getExecutorCoreSize() { return executorCoreSize; }
    public void setExecutorCoreSize(int executorCoreSize) { this.executorCoreSize = executorCoreSize; }
    public int getExecutorMaxSize() { return executorMaxSize; }
    public void setExecutorMaxSize(int executorMaxSize) { this.executorMaxSize = executorMaxSize; }
    public int getExecutorQueueCapacity() { return executorQueueCapacity; }
    public void setExecutorQueueCapacity(int executorQueueCapacity) { this.executorQueueCapacity = executorQueueCapacity; }
}
