package com.enterprise.oms.inventory.cache;

import com.enterprise.oms.inventory.service.ProductView;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional read-through cache for {@code GET /products/{id}} ONLY.
 * <p>
 * <b>Staleness contract</b> (also in the README): entries expire after {@code oms.inventory.cache.ttl-seconds} and are
 * evicted after every commit that changes the product or its stock, so a read can only be stale in a small window
 * (read-before-commit then put-after-evict) or when an in-memory instance misses a change made on ANOTHER instance
 * (bounded by the TTL; the Redis implementation is shared and does not have that gap). Reservation decisions, the
 * internal validate endpoint and every stock/inventory endpoint read the database directly: {@code available} from a
 * cache is NEVER used to decide whether stock can be reserved.
 * <p>
 * Implementations must never throw: a cache failure is a miss.
 */
public interface ProductCache {

    Optional<ProductView> get(UUID productId);

    void put(ProductView view);

    void evict(UUID productId);

    default void evictAll(Collection<UUID> productIds) {
        productIds.forEach(this::evict);
    }

    void clear();

    /** "memory" or "redis" (reported in logs/tests). */
    String backend();
}
