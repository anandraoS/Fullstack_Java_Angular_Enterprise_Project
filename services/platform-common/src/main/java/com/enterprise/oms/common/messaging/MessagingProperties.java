package com.enterprise.oms.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** {@code oms.messaging.*}. */
@ConfigurationProperties(prefix = "oms.messaging")
public class MessagingProperties {

    /** Toggles consumer infrastructure (processor, container factory, metrics, status service). */
    private boolean enabled = true;
    /** Consumer group id; defaults to spring.application.name. */
    private String consumerGroup;
    /** Listener threads per container. */
    private int concurrency = 1;
    /** Expose GET /api/v1/ops/messaging (OPERATOR/ADMIN). */
    private boolean opsEndpointEnabled = true;
    /** Topics the service consumes; reported by the ops endpoint (lag is computed for these). */
    private List<String> topics = new ArrayList<>();
    /** AdminClient timeout used by the status service. */
    private long adminTimeoutMs = 3000;
    private final Retry retry = new Retry();

    public static class Retry {
        /** Total delivery attempts (1 initial + n-1 retries). */
        private int maxAttempts = 4;
        private long initialIntervalMs = 500;
        private double multiplier = 2.0;
        private long maxIntervalMs = 5000;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialIntervalMs() { return initialIntervalMs; }
        public void setInitialIntervalMs(long initialIntervalMs) { this.initialIntervalMs = initialIntervalMs; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public long getMaxIntervalMs() { return maxIntervalMs; }
        public void setMaxIntervalMs(long maxIntervalMs) { this.maxIntervalMs = maxIntervalMs; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public boolean isOpsEndpointEnabled() { return opsEndpointEnabled; }
    public void setOpsEndpointEnabled(boolean opsEndpointEnabled) { this.opsEndpointEnabled = opsEndpointEnabled; }
    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }
    public long getAdminTimeoutMs() { return adminTimeoutMs; }
    public void setAdminTimeoutMs(long adminTimeoutMs) { this.adminTimeoutMs = adminTimeoutMs; }
    public Retry getRetry() { return retry; }
}
