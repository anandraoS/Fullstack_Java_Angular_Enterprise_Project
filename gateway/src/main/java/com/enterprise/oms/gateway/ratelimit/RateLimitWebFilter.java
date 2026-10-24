package com.enterprise.oms.gateway.ratelimit;

import com.enterprise.oms.gateway.config.GatewayProperties;
import com.enterprise.oms.gateway.filter.EdgeFilters;
import com.enterprise.oms.gateway.problem.Problems;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Token-bucket rate limiting keyed by the validated JWT subject ({@code sub:...}) or, for unauthenticated requests
 * (e.g. the dev token endpoint), by the TCP peer address ({@code ip:...}). Runs after authentication, so a forged token cannot
 * create buckets. Over-limit requests get 429 problem+json with {@code Retry-After}. Actuator probes are exempt.
 * Behind a load balancer the peer address is the LB (all anonymous callers share one bucket); authenticated callers are unaffected.
 */
@Component
public class RateLimitWebFilter implements WebFilter, Ordered {

    private final EdgeRateLimiter limiter;
    private final Problems problems;
    private final boolean enabled;

    public RateLimitWebFilter(EdgeRateLimiter limiter, Problems problems, GatewayProperties props) {
        this.limiter = limiter;
        this.problems = problems;
        this.enabled = props.getRateLimit().isEnabled();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled || exchange.getRequest().getPath().value().startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(a -> keyOf(a, exchange))
                .defaultIfEmpty(ipKey(exchange))
                .flatMap(limiter::acquire)
                .flatMap(decision -> {
                    exchange.getResponse().beforeCommit(() -> {
                        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", Long.toString(decision.remaining()));
                        return Mono.empty();
                    });
                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too Many Requests",
                            "Rate limit exceeded. Retry later.",
                            Map.of("Retry-After", Long.toString(decision.retryAfterSeconds())));
                });
    }

    private static String keyOf(Authentication auth, ServerWebExchange exchange) {
        if (auth instanceof JwtAuthenticationToken jwt && auth.isAuthenticated() && jwt.getName() != null) {
            return "sub:" + jwt.getName();
        }
        return ipKey(exchange);
    }

    static String ipKey(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress());
    }

    @Override
    public int getOrder() {
        return EdgeFilters.Order.RATE_LIMIT;
    }
}
