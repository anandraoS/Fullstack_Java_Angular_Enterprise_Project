package com.enterprise.oms.gateway.auth;

import com.enterprise.oms.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Brute-force protection for the dev token endpoint: failed attempts are counted per (ip, username) and per ip inside a
 * sliding window; reaching the limit locks that key for the lockout duration (even the correct password is then refused with
 * 429). Success clears the (ip, username) counter only. In-memory, per instance, bounded.
 */
@Component
@Profile("dev")
public class LoginThrottle {

    private static final int MAX_USERNAME = 64;

    private final int maxFailures;
    private final int maxFailuresPerIp;
    private final long windowNanos;
    private final long lockoutNanos;
    private final int maxKeys;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottle(GatewayProperties props) {
        this(props.getThrottle(), System::nanoTime);
    }

    LoginThrottle(GatewayProperties.Throttle t, LongSupplier clock) {
        this.maxFailures = t.getMaxFailures();
        this.maxFailuresPerIp = t.getMaxFailuresPerIp();
        this.windowNanos = t.getWindow().toNanos();
        this.lockoutNanos = t.getLockout().toNanos();
        this.maxKeys = t.getMaxKeys();
        this.clock = clock;
    }

    /** Remaining lockout, or {@link Duration#ZERO} when attempts are allowed. */
    public Duration lockedFor(String ip, String username) {
        long now = clock.getAsLong();
        long remaining = Math.max(remaining(userKey(ip, username), now), remaining(ipKey(ip), now));
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    public void recordFailure(String ip, String username) {
        long now = clock.getAsLong();
        if (entries.size() >= maxKeys) {
            entries.entrySet().removeIf(e -> e.getValue().expired(now, windowNanos));
        }
        if (entries.size() >= maxKeys) {
            return; // memory protection: the per-IP lock below still applies for existing keys
        }
        entries.computeIfAbsent(userKey(ip, username), k -> new Entry()).failure(now, windowNanos, maxFailures, lockoutNanos);
        entries.computeIfAbsent(ipKey(ip), k -> new Entry()).failure(now, windowNanos, maxFailuresPerIp, lockoutNanos);
    }

    public void recordSuccess(String ip, String username) {
        entries.remove(userKey(ip, username));
    }

    private long remaining(String key, long now) {
        Entry e = entries.get(key);
        return e == null ? 0 : e.lockRemaining(now);
    }

    private static String userKey(String ip, String username) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return "u:" + ip + "|" + (u.length() > MAX_USERNAME ? u.substring(0, MAX_USERNAME) : u);
    }

    private static String ipKey(String ip) {
        return "i:" + ip;
    }

    private static final class Entry {
        private int failures;
        private long windowStart;
        private long lockedUntil;

        synchronized void failure(long now, long window, int max, long lockout) {
            if (failures == 0 || now - windowStart > window) {
                failures = 0;
                windowStart = now;
            }
            failures++;
            if (failures >= max) {
                lockedUntil = now + lockout;
                failures = 0;
            }
        }

        synchronized long lockRemaining(long now) {
            return lockedUntil - now;
        }

        synchronized boolean expired(long now, long window) {
            return lockedUntil - now <= 0 && now - windowStart > window;
        }
    }
}
