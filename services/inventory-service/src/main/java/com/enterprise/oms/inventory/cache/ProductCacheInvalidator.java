package com.enterprise.oms.inventory.cache;

import com.enterprise.oms.inventory.service.TxHooks;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Evicts products from the read cache once the changing transaction has committed (never before: see ProductCache). */
@Component
public class ProductCacheInvalidator {

    private final ProductCache cache;

    public ProductCacheInvalidator(ProductCache cache) {
        this.cache = cache;
    }

    public void evictAfterCommit(Collection<UUID> productIds) {
        List<UUID> ids = List.copyOf(productIds);
        TxHooks.afterCommit(() -> cache.evictAll(ids));
    }

    public void evictAfterCommit(UUID productId) {
        evictAfterCommit(List.of(productId));
    }
}
