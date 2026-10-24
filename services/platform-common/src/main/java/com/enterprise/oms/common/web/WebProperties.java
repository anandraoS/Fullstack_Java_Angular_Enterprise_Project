package com.enterprise.oms.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.web.*}. */
@ConfigurationProperties(prefix = "oms.web")
public class WebProperties {

    /** Toggles filters + exception handler. */
    private boolean enabled = true;
    /** Max request body (bytes), contract: 64 KB. */
    private long maxBodyBytes = 64 * 1024;
    /** Max page size accepted by PageRequestFactory. */
    private int maxPageSize = 100;
    private int defaultPageSize = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(long maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }
}
