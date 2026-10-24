package com.enterprise.oms.inventory.service;

import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Stock;

import java.math.BigDecimal;
import java.util.UUID;

/** Internal read model: catalog row + stock numbers. Shaped per caller role by {@code ProductResponse}. */
public record ProductView(UUID id, String sku, String name, String description, String category, BigDecimal price,
                          String currency, boolean active, int onHand, int reserved, int available, long version) {

    public static ProductView of(Product p, Stock s) {
        int onHand = s == null ? 0 : s.getOnHand();
        int reserved = s == null ? 0 : s.getReserved();
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getCategory(), p.getPrice(),
                p.getCurrency(), p.isActive(), onHand, reserved, onHand - reserved,
                p.getVersion() == null ? 0 : p.getVersion());
    }
}
