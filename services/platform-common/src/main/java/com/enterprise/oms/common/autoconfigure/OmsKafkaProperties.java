package com.enterprise.oms.common.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.kafka.*}: producer timeouts of {@code omsKafkaTemplate} (outbox relay + DLT publishing). */
@ConfigurationProperties(prefix = "oms.kafka")
public class OmsKafkaProperties {

    /** Creates {@code omsKafkaTemplate}. */
    private boolean enabled = true;
    /** Max time send() may block waiting for metadata (keeps the relay responsive when Kafka is down). */
    private int maxBlockMs = 3000;
    private int requestTimeoutMs = 5000;
    private int deliveryTimeoutMs = 10000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxBlockMs() { return maxBlockMs; }
    public void setMaxBlockMs(int maxBlockMs) { this.maxBlockMs = maxBlockMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getDeliveryTimeoutMs() { return deliveryTimeoutMs; }
    public void setDeliveryTimeoutMs(int deliveryTimeoutMs) { this.deliveryTimeoutMs = deliveryTimeoutMs; }
}
