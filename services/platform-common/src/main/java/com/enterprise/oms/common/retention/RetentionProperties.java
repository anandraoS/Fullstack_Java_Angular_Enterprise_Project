package com.enterprise.oms.common.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code oms.retention.*}: purge of the append-only bookkeeping tables that otherwise grow without bound.
 * {@code processed-events-retention} must be longer than the longest period in which a broker could re-deliver
 * an event (topic retention, DLT replay window); deleting a marker earlier re-opens that event to double processing.
 */
@ConfigurationProperties(prefix = "oms.retention")
public class RetentionProperties {

    private boolean enabled = true;
    private long intervalMs = 3_600_000;
    private Duration processedEventsRetention = Duration.ofDays(14);
    private Duration auditEventsRetention = Duration.ofDays(365);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public Duration getProcessedEventsRetention() { return processedEventsRetention; }
    public void setProcessedEventsRetention(Duration d) { this.processedEventsRetention = d; }
    public Duration getAuditEventsRetention() { return auditEventsRetention; }
    public void setAuditEventsRetention(Duration d) { this.auditEventsRetention = d; }
}
