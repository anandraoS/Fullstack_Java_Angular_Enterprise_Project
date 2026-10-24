package com.enterprise.oms.inventory.cache;

import com.enterprise.oms.inventory.service.ProductView;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Simple bounded TTL map (no external library): per-instance, lost on restart. Default when Redis is off. */
public class InMemoryProductCache implements ProductCache {

    private record Entry(ProductView view, long expiresAtMillis) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final Clock clock;

    public InMemoryProductCache(int ttlSeconds, int maxEntries, Clock clock) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    @Override
    public Optional<ProductView> get(UUID productId) {
        Entry e = entries.get(productId);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiresAtMillis() <= clock.millis()) {
            entries.remove(productId, e);
            return Optional.empty();
        }
        return Optional.of(e.view());
    }

    @Override
    public void put(ProductView view) {
        if (ttlMillis <= 0) {
            return;
        }
        if (entries.size() >= maxEntries) {
            long now = clock.millis();
            entries.values().removeIf(e -> e.expiresAtMillis() <= now);
            if (entries.size() >= maxEntries) {
                entries.clear();   // crude but bounded: a full cache of live entries is simply rebuilt
            }
        }
        entries.put(view.id(), new Entry(view, clock.millis() + ttlMillis));
    }

    @Override
    public void evict(UUID productId) {
        entries.remove(productId);
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public String backend() {
        return "memory";
    }

    public int size() {
        return entries.size();
    }
}
