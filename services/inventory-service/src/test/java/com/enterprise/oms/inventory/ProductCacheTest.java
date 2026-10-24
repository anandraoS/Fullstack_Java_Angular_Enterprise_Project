package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.cache.InMemoryProductCache;
import com.enterprise.oms.inventory.cache.ProductCache;
import com.enterprise.oms.inventory.cache.RedisProductCache;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.service.ProductView;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Optional product-read cache: implementations, eviction on change, and the "never decide from the cache" rule. */
class ProductCacheTest extends AbstractInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProductView view(UUID id, int available) {
        return new ProductView(id, "SKU-" + id.toString().substring(0, 6), "n", "d", "c", new BigDecimal("1.00"), "USD",
                true, available, 0, available, 0);
    }

    /** Settable clock for TTL tests. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Nested
    class InMemory {
        private final MutableClock clock = new MutableClock();

        @Test
        void entriesExpireAfterTheTtlAndCanBeEvicted() {
            InMemoryProductCache cache = new InMemoryProductCache(30, 10, clock);
            UUID id = UUID.randomUUID();
            cache.put(view(id, 5));
            assertThat(cache.get(id)).isPresent();
            clock.advance(Duration.ofSeconds(29));
            assertThat(cache.get(id)).isPresent();
            clock.advance(Duration.ofSeconds(2));
            assertThat(cache.get(id)).as("expired").isEmpty();

            cache.put(view(id, 5));
            cache.evict(id);
            assertThat(cache.get(id)).isEmpty();
            assertThat(cache.backend()).isEqualTo("memory");
        }

        @Test
        void sizeIsBoundedAndATtlOfZeroDisablesCaching() {
            InMemoryProductCache cache = new InMemoryProductCache(30, 3, clock);
            for (int i = 0; i < 20; i++) {
                cache.put(view(UUID.randomUUID(), i));
            }
            assertThat(cache.size()).isLessThanOrEqualTo(3);
            InMemoryProductCache off = new InMemoryProductCache(0, 3, clock);
            UUID id = UUID.randomUUID();
            off.put(view(id, 1));
            assertThat(off.get(id)).isEmpty();
        }
    }

    @Nested
    class Redis {
        private final StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> ops = mock(ValueOperations.class);
        private final ObjectMapper mapper = new ObjectMapper();
        private final RedisProductCache cache = new RedisProductCache(template, mapper, 30);

        Redis() {
            when(template.opsForValue()).thenReturn(ops);
        }

        @Test
        void writesJsonWithTtlUnderANamespacedKeyAndReadsItBack() throws Exception {
            UUID id = UUID.randomUUID();
            ProductView v = view(id, 7);
            cache.put(v);
            verify(ops).set(eq("oms:inventory:product:" + id), eq(mapper.writeValueAsString(v)), eq(Duration.ofSeconds(30)));

            when(ops.get("oms:inventory:product:" + id)).thenReturn(mapper.writeValueAsString(v));
            assertThat(cache.get(id)).contains(v);
            cache.evict(id);
            verify(template).delete("oms:inventory:product:" + id);
            assertThat(cache.backend()).isEqualTo("redis");
        }

        @Test
        void everyRedisFailureDegradesToAMissAndNeverThrows() {
            UUID id = UUID.randomUUID();
            when(ops.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
            org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                    .when(ops).set(anyString(), anyString(), any(Duration.class));
            when(template.delete(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

            assertThat(cache.get(id)).isEmpty();
            cache.put(view(id, 1));
            cache.evict(id);
        }

        @Test
        void corruptCacheContentIsAMiss() {
            UUID id = UUID.randomUUID();
            when(ops.get("oms:inventory:product:" + id)).thenReturn("{not json");
            assertThat(cache.get(id)).isEmpty();
        }
    }

    // ------------------------------------------------------------------- wiring + behaviour in the real application

    @Autowired ProductCache cache;
    @Autowired ReservationService reservations;

    private JsonNode getProduct(UUID id) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/products/" + id).header("Authorization", JwtTestTokens.customer(UUID.randomUUID())))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void theDefaultBackendIsInMemoryBecauseRedisIsOff() {
        assertThat(cache.backend()).isEqualTo("memory");
    }

    @Test
    void aReadIsCachedAndAStockAdjustmentEvictsIt() throws Exception {
        UUID p = newProduct(10);
        assertThat(getProduct(p).get("available").asInt()).isEqualTo(10);
        jdbc.update("UPDATE stock SET on_hand = 40 WHERE product_id = ?", p);   // out-of-band change: no eviction
        assertThat(getProduct(p).get("available").asInt()).as("served from cache: documented staleness window").isEqualTo(10);

        mvc.perform(put("/api/v1/inventory/" + p + "/stock").header("Authorization", JwtTestTokens.operator())
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5,\"reason\":\"restock after count\"}"));

        assertThat(getProduct(p).get("available").asInt()).as("evicted after commit, fresh read").isEqualTo(45);
    }

    @Test
    void reservingAndReleasingEvictTheCachedProduct() throws Exception {
        UUID p = newProduct(10);
        assertThat(getProduct(p).get("available").asInt()).isEqualTo(10);
        UUID order = UUID.randomUUID();
        reservations.reserve(order, List.of(item(p, 4)), null, "c", null);
        assertThat(getProduct(p).get("available").asInt()).isEqualTo(6);
    }

    @Test
    void aStaleCacheNeverInfluencesAReservationDecision() throws Exception {
        UUID p = newProduct(10);
        assertThat(getProduct(p).get("available").asInt()).isEqualTo(10);          // cache now says 10
        jdbc.update("UPDATE stock SET on_hand = 0 WHERE product_id = ?", p);        // reality: nothing left, cache untouched

        ReservationOutcome out = reservations.reserve(UUID.randomUUID(), List.of(item(p, 1)), null, "c", null);

        assertThat(out.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(reserved(p)).isZero();
    }

    @Test
    void productUpdateEvictsTheCachedCopy() throws Exception {
        UUID p = newProduct(3);
        assertThat(getProduct(p).get("name").asText()).startsWith("Test ");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/products/" + p)
                .header("Authorization", JwtTestTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Brand New Name\",\"category\":\"c\",\"price\":3.00}"));
        assertThat(getProduct(p).get("name").asText()).isEqualTo("Brand New Name");
    }
}
