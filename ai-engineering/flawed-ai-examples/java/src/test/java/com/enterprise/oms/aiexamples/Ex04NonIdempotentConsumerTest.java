package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex04NonIdempotentConsumer.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Ex04NonIdempotentConsumerTest {

    private final InventoryReleased event = new InventoryReleased("evt-1", "o-1", 5);

    @Test
    void flawedConsumerReleasesStockTwiceOnRedelivery() {
        Stock stock = new Stock(10);                      // 10 reserved: 5 for o-1, 5 for another order
        FlawedConsumer consumer = new FlawedConsumer(stock);

        consumer.onEvent(event);
        consumer.onEvent(event);                          // redelivery after a rebalance or relay crash

        assertEquals(0, stock.reserved(), "BUG MANIFESTS: expected 5 reserved to remain, other order lost its hold");
    }

    @Test
    void idempotentConsumerAppliesRedeliveryOnce() {
        Stock stock = new Stock(10);
        IdempotentConsumer consumer = new IdempotentConsumer(e -> stock.releaseReserved(e.quantity()));

        assertTrue(consumer.onEvent(event));
        assertFalse(consumer.onEvent(event));

        assertEquals(5, stock.reserved());
    }

    @Test
    void idempotentConsumerHandlesConcurrentDuplicates() throws Exception {
        Stock stock = new Stock(10);
        IdempotentConsumer consumer = new IdempotentConsumer(e -> stock.releaseReserved(e.quantity()));
        AtomicInteger applied = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                if (consumer.onEvent(event)) applied.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, applied.get());
        assertEquals(5, stock.reserved());
    }

    @Test
    void failedEffectDoesNotBurnTheEventIdSoRetrySucceeds() {
        Stock stock = new Stock(10);
        AtomicInteger calls = new AtomicInteger();
        IdempotentConsumer consumer = new IdempotentConsumer(e -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("db blip");
            stock.releaseReserved(e.quantity());
        });

        assertThrows(IllegalStateException.class, () -> consumer.onEvent(event));
        assertTrue(consumer.onEvent(event), "retry must still apply the effect");

        assertEquals(5, stock.reserved());
    }
}
