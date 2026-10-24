package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex10SingletonState.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class Ex10SingletonStateTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private static Runnable await(CyclicBarrier b) {
        return () -> {
            try { b.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
        };
    }

    @Test
    void flawedSharedFieldGivesAtLeastOneCustomerTheWrongPrice() throws Exception {
        FlawedPricingService shared = new FlawedPricingService();
        CyclicBarrier bothHaveStarted = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BigDecimal> gold = pool.submit(() -> {
                shared.startQuote(Tier.GOLD);
                return shared.finishQuote(HUNDRED, await(bothHaveStarted));
            });
            Future<BigDecimal> standard = pool.submit(() -> {
                shared.startQuote(Tier.STANDARD);
                return shared.finishQuote(HUNDRED, await(bothHaveStarted));
            });

            boolean goldCorrect = gold.get(10, TimeUnit.SECONDS).compareTo(new BigDecimal("90.00")) == 0;
            boolean standardCorrect = standard.get(10, TimeUnit.SECONDS).compareTo(new BigDecimal("100.00")) == 0;

            assertFalse(goldCorrect && standardCorrect,
                    "BUG MANIFESTS: one request's tier overwrote the other's (goldCorrect=" + goldCorrect
                            + ", standardCorrect=" + standardCorrect + ")");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fixedStatelessServiceIsCorrectUnderTheSameInterleaving() throws Exception {
        FixedPricingService shared = new FixedPricingService();
        CyclicBarrier both = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BigDecimal> gold = pool.submit(() -> shared.quote(Tier.GOLD, HUNDRED, await(both)));
            Future<BigDecimal> standard = pool.submit(() -> shared.quote(Tier.STANDARD, HUNDRED, await(both)));

            assertEquals(0, gold.get(10, TimeUnit.SECONDS).compareTo(new BigDecimal("90.00")));
            assertEquals(0, standard.get(10, TimeUnit.SECONDS).compareTo(new BigDecimal("100.00")));
        } finally {
            pool.shutdownNow();
        }
    }
}
