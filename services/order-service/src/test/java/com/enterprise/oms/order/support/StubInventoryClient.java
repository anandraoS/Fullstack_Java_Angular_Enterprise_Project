package com.enterprise.oms.order.support;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.order.saga.InventoryClient;
import com.enterprise.oms.order.saga.InventoryUnavailableException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for inventory-service's validation endpoint. Unknown products are invalid. Switch
 * {@link #setUnavailable(boolean)} to simulate an outage (transient failure).
 */
public class StubInventoryClient implements InventoryClient {

    public record Product(UUID id, String sku, String name, BigDecimal price, int available, boolean active) {
    }

    private final Map<UUID, Product> products = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private final AtomicInteger calls = new AtomicInteger();

    public Product addProduct(String sku, String price, int available) {
        Product p = new Product(UUID.randomUUID(), sku, "Product " + sku, new BigDecimal(price), available, true);
        products.put(p.id(), p);
        return p;
    }

    public Product addInactiveProduct(String sku, String price) {
        Product p = new Product(UUID.randomUUID(), sku, "Product " + sku, new BigDecimal(price), 100, false);
        products.put(p.id(), p);
        return p;
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ValidationResult validate(List<EventPayloads.QuantityItem> items) {
        calls.incrementAndGet();
        if (unavailable) {
            throw new InventoryUnavailableException("stub inventory is down", null);
        }
        boolean all = true;
        List<Line> lines = items.stream().map(i -> {
            Product p = products.get(i.productId());
            if (p == null) {
                return new Line(i.productId(), null, null, null, 0, false, false, "UNKNOWN_PRODUCT");
            }
            if (!p.active()) {
                return new Line(p.id(), p.sku(), p.name(), p.price(), p.available(), false, false, "INACTIVE");
            }
            if (p.available() < i.quantity()) {
                return new Line(p.id(), p.sku(), p.name(), p.price(), p.available(), true, false, "INSUFFICIENT_STOCK");
            }
            return new Line(p.id(), p.sku(), p.name(), p.price(), p.available(), true, true, null);
        }).toList();
        for (Line l : lines) {
            all &= l.ok();
        }
        return new ValidationResult(all, lines);
    }
}
