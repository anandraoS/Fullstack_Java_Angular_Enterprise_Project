package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex01Oversell.FixedReservationService;
import com.enterprise.oms.aiexamples.Ex01Oversell.FlawedReservationService;
import com.enterprise.oms.aiexamples.Ex01Oversell.StockRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Ex01OversellTest {

    private static Runnable barrier(CyclicBarrier b) {
        return () -> {
            try { b.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
        };
    }

    private static int runConcurrently(int threads, java.util.function.BooleanSupplier call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) fs.add(pool.submit(call::getAsBoolean));
            int ok = 0;
            for (Future<Boolean> f : fs) if (f.get(10, TimeUnit.SECONDS)) ok++;
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void flawedVersionOversellsWhenTwoRequestsInterleave() throws Exception {
        StockRow row = new StockRow(1);                       // only ONE unit exists
        CyclicBarrier both = new CyclicBarrier(2);            // force both to finish the check before either acts
        FlawedReservationService svc = new FlawedReservationService(row, barrier(both));

        int accepted = runConcurrently(2, () -> svc.reserve(1));

        assertEquals(2, accepted, "BUG MANIFESTS: both requests were told they got the last unit");
        assertTrue(row.reserved() > row.onHand(), "reserved=" + row.reserved() + " exceeds onHand=" + row.onHand());
    }

    @Test
    void fixedVersionNeverOversellsUnderSameInterleaving() throws Exception {
        StockRow row = new StockRow(1);
        CyclicBarrier both = new CyclicBarrier(2);
        FixedReservationService svc = new FixedReservationService(row, barrier(both));

        int accepted = runConcurrently(2, () -> svc.reserve(1));

        assertEquals(1, accepted);
        assertEquals(1, row.reserved());
        assertTrue(row.available() >= 0);
    }

    @Test
    void fixedVersionStressNeverExceedsOnHand() throws Exception {
        StockRow row = new StockRow(25);
        FixedReservationService svc = new FixedReservationService(row, () -> { });
        AtomicInteger accepted = new AtomicInteger();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 20; i++) if (svc.reserve(1)) accepted.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(25, accepted.get(), "320 attempts, exactly 25 units can be reserved");
        assertEquals(25, row.reserved());
    }
}
