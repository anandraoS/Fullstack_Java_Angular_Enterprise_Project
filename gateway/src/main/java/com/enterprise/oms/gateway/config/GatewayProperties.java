package com.enterprise.oms.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code oms.gateway.*}. Every value has a safe default; route targets are overridable with
 * {@code OMS_GATEWAY_ROUTES_{ORDER|INVENTORY|FULFILLMENT|NOTIFICATION}_URI} (relaxed binding).
 */
@ConfigurationProperties(prefix = "oms.gateway")
public class GatewayProperties {

    private final Routes routes = new Routes();
    private final Cors cors = new Cors();
    private final RateLimit rateLimit = new RateLimit();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Bulkhead bulkhead = new Bulkhead();
    private final Health health = new Health();
    private final Throttle throttle = new Throttle();
    /** Maximum request body accepted at the edge (contract section 5: 64 KB). */
    private int maxBodyBytes = 65536;

    public static class Routes {
        private final Target order = new Target("http://localhost:18081");
        private final Target inventory = new Target("http://localhost:18082");
        private final Target fulfillment = new Target("http://localhost:18083");
        private final Target notification = new Target("http://localhost:18084");

        public Target getOrder() { return order; }
        public Target getInventory() { return inventory; }
        public Target getFulfillment() { return fulfillment; }
        public Target getNotification() { return notification; }
    }

    public static class Target {
        private String uri;

        public Target(String uri) { this.uri = uri; }

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }

        /** Validated URI: http/https only, with a host. */
        public URI toUri() {
            URI u = URI.create(uri);
            if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                throw new IllegalStateException("Invalid gateway route target (http/https with host required)");
            }
            return u;
        }
    }

    public static class Cors {
        /** Exact origins allowed to call the API cross-origin. Empty (default) = same-origin only; "*" is rejected. */
        private List<String> allowedOrigins = new ArrayList<>();
        private Duration maxAge = Duration.ofHours(1);

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** Bucket size (burst) per key. */
        private int capacity = 100;
        /** Tokens added per second per key. */
        private double refillPerSecond = 50;
        /** Upper bound of tracked keys in the in-memory limiter (memory protection). */
        private int maxKeys = 100_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public double getRefillPerSecond() { return refillPerSecond; }
        public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }
        public int getMaxKeys() { return maxKeys; }
        public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }
    }

    public static class CircuitBreaker {
        private int slidingWindowSize = 20;
        private int minimumCalls = 10;
        private float failureRatePercent = 50;
        private Duration waitInOpenState = Duration.ofSeconds(10);
        /** Must be larger than the netty response timeout (10s) so the HTTP client times out first. */
        private Duration timeLimit = Duration.ofSeconds(12);

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }
        public int getMinimumCalls() { return minimumCalls; }
        public void setMinimumCalls(int v) { this.minimumCalls = v; }
        public float getFailureRatePercent() { return failureRatePercent; }
        public void setFailureRatePercent(float v) { this.failureRatePercent = v; }
        public Duration getWaitInOpenState() { return waitInOpenState; }
        public void setWaitInOpenState(Duration v) { this.waitInOpenState = v; }
        public Duration getTimeLimit() { return timeLimit; }
        public void setTimeLimit(Duration v) { this.timeLimit = v; }
    }

    /**
     * Concurrent in-flight calls the gateway admits PER DOWNSTREAM SERVICE (Resilience4j semaphore bulkhead that Spring Cloud
     * CircuitBreaker always wraps around a reactive circuit-breaker filter). The library default is 25 with no waiting, which
     * answered 503 BulkheadFullException to the 26th concurrent request in the live 30-parallel-order run.
     */
    public static class Bulkhead {
        private int maxConcurrentCalls = 200;
        /** How long a call may wait for a free slot before it is shed with 503. */
        private Duration maxWait = Duration.ofMillis(500);

        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int v) { this.maxConcurrentCalls = v; }
        public Duration getMaxWait() { return maxWait; }
        public void setMaxWait(Duration v) { this.maxWait = v; }
    }

    public static class Health {
        /** Per-service readiness probe timeout of GET /api/v1/ops/health. */
        private Duration timeout = Duration.ofSeconds(2);

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    /** Brute-force throttling of the dev token endpoint. */
    public static class Throttle {
        /** Failed attempts per (ip, username) inside {@code window} before a lockout. */
        private int maxFailures = 5;
        /** Failed attempts per ip (any username) inside {@code window} before a lockout. */
        private int maxFailuresPerIp = 20;
        private Duration window = Duration.ofMinutes(5);
        private Duration lockout = Duration.ofMinutes(5);
        private int maxKeys = 50_000;

        public int getMaxFailures() { return maxFailures; }
        public void setMaxFailures(int v) { this.maxFailures = v; }
        public int getMaxFailuresPerIp() { return maxFailuresPerIp; }
        public void setMaxFailuresPerIp(int v) { this.maxFailuresPerIp = v; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration v) { this.window = v; }
        public Duration getLockout() { return lockout; }
        public void setLockout(Duration v) { this.lockout = v; }
        public int getMaxKeys() { return maxKeys; }
        public void setMaxKeys(int v) { this.maxKeys = v; }
    }

    public Routes getRoutes() { return routes; }
    public Cors getCors() { return cors; }
    public RateLimit getRateLimit() { return rateLimit; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public Bulkhead getBulkhead() { return bulkhead; }
    public Health getHealth() { return health; }
    public Throttle getThrottle() { return throttle; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
}
