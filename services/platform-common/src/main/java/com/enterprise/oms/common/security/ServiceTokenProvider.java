package com.enterprise.oms.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Provides a short-lived SERVICE JWT ({@code sub=svc-<service>}, roles [SERVICE]) for service-to-service calls.
 * Cached and renewed shortly before expiry.
 */
public class ServiceTokenProvider {

    private static final Duration RENEW_BEFORE = Duration.ofSeconds(20);

    private final JwtTokenFactory factory;
    private final String subject;
    private final Duration ttl;
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ServiceTokenProvider(JwtTokenFactory factory, String serviceName, Duration ttl) {
        this.factory = factory;
        this.subject = "svc-" + serviceName;
        this.ttl = ttl;
    }

    public String subject() {
        return subject;
    }

    public synchronized String token() {
        Instant now = Instant.now();
        if (token == null || now.isAfter(expiresAt.minus(RENEW_BEFORE))) {
            token = factory.issue(subject, List.of(Roles.SERVICE), null, ttl);
            expiresAt = now.plus(ttl);
        }
        return token;
    }
}
