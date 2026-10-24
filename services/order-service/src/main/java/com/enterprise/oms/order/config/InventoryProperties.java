package com.enterprise.oms.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oms.inventory.*}: how to reach inventory-service for the synchronous validation call. */
@ConfigurationProperties(prefix = "oms.inventory")
public class InventoryProperties {

    /** Env OMS_INVENTORY_BASE_URL. */
    private String baseUrl = "http://localhost:18082";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
