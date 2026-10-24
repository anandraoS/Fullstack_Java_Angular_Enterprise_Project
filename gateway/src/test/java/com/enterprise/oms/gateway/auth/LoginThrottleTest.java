package com.enterprise.oms.gateway.auth;

import com.enterprise.oms.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private LoginThrottle throttle() {
        GatewayProperties.Throttle t = new GatewayProperties.Throttle();
        t.setMaxFailures(3);
        t.setMaxFailuresPerIp(5);
        t.setWindow(Duration.ofMinutes(1));
        t.setLockout(Duration.ofMinutes(2));
        return new LoginThrottle(t, nanos::get);
    }

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterTheLockout() {
        LoginThrottle th = throttle();
        for (int i = 0; i < 3; i++) {
            assertThat(th.lockedFor("1.1.1.1", "alice")).isEqualTo(Duration.ZERO);
            th.recordFailure("1.1.1.1", "alice");
        }
        assertThat(th.lockedFor("1.1.1.1", "alice")).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(2));
        assertThat(th.lockedFor("1.1.1.1", "ALICE ")).as("username normalised").isPositive();
        assertThat(th.lockedFor("2.2.2.2", "alice")).as("other ip").isEqualTo(Duration.ZERO);
        nanos.addAndGet(Duration.ofMinutes(2).toNanos() + 1);
        assertThat(th.lockedFor("1.1.1.1", "alice")).isEqualTo(Duration.ZERO);
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        LoginThrottle th = throttle();
        th.recordFailure("1.1.1.1", "bob");
        th.recordFailure("1.1.1.1", "bob");
        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        th.recordFailure("1.1.1.1", "bob");
        assertThat(th.lockedFor("1.1.1.1", "bob")).isEqualTo(Duration.ZERO);
    }

    @Test
    void successClearsThePairCounterAndPerIpLockCoversAllUsernames() {
        LoginThrottle th = throttle();
        th.recordFailure("1.1.1.1", "carol");
        th.recordFailure("1.1.1.1", "carol");
        th.recordSuccess("1.1.1.1", "carol");
        th.recordFailure("1.1.1.1", "carol");
        assertThat(th.lockedFor("1.1.1.1", "carol")).isEqualTo(Duration.ZERO);
        // 3 failures so far on this ip; two more usernames push it to the per-ip limit of 5
        th.recordFailure("1.1.1.1", "x1");
        th.recordFailure("1.1.1.1", "x2");
        assertThat(th.lockedFor("1.1.1.1", "anyone")).isPositive();
    }
}
