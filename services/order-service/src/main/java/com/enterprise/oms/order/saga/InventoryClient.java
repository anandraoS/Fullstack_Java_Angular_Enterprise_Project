package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.EventPayloads;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Synchronous validation call to inventory-service ({@code POST /api/v1/internal/products/validate}). */
public interface InventoryClient {

    /**
     * @throws InventoryUnavailableException transient failure (timeouts, 5xx, circuit open) after retries
     * @throws InventoryCallException        non-transient failure (4xx: misconfiguration / contract break)
     */
    ValidationResult validate(List<EventPayloads.QuantityItem> items);

    /** Response of the validate endpoint (tolerant reader: unknown fields are ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ValidationResult(boolean valid, List<Line> lines) {
        public List<Line> safeLines() {
            return lines == null ? List.of() : lines;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Line(UUID productId, String sku, String name, BigDecimal unitPrice, Integer available, boolean active,
                boolean ok, String reason) {
    }
}
