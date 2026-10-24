package com.enterprise.oms.aiexamples;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Example 10: mutable per-request state in a singleton. Spring beans are singletons shared by every request thread;
 * a field written at the start of a request and read at the end is a data race and a cross-customer data leak.
 */
public final class Ex10SingletonState {

    private Ex10SingletonState() { }

    public enum Tier {
        STANDARD("0.00"), GOLD("0.10");
        final BigDecimal discount;
        Tier(String d) { this.discount = new BigDecimal(d); }
    }

    /** Flawed: the "current" tier lives in a field of the shared bean. */
    public static final class FlawedPricingService {
        private Tier currentTier;                    // shared by all threads

        public void startQuote(Tier tier) { this.currentTier = tier; }

        public BigDecimal finishQuote(BigDecimal amount, Runnable betweenStartAndFinish) {
            betweenStartAndFinish.run();             // test seam: another request runs in the gap
            return amount.subtract(amount.multiply(currentTier.discount)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** Fixed: stateless; everything the computation needs is a parameter or a local. */
    public static final class FixedPricingService {
        public BigDecimal quote(Tier tier, BigDecimal amount, Runnable betweenStartAndFinish) {
            betweenStartAndFinish.run();
            return amount.subtract(amount.multiply(tier.discount)).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
