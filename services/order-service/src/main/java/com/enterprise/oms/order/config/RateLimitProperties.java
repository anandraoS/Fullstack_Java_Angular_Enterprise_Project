package com.enterprise.oms.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.order.rate-limit.*}: token bucket per caller on POST /orders. */
@ConfigurationProperties(prefix = "oms.order.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    /** Bucket size = burst allowance. */
    private int capacity = 20;
    /** Sustained rate (tokens added per second). */
    private double refillPerSecond = 5.0;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public double getRefillPerSecond() { return refillPerSecond; }
    public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }
}
