package com.enterprise.oms.gateway.filter;

import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.gateway.problem.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap, security-relevant edge filters that run BEFORE Spring Security (order values in {@link Order}):
 * correlation id, access log, untrusted header stripping and the path guard (internal-path block + traversal rejection).
 */
public final class EdgeFilters {

    private EdgeFilters() {
    }

    /** Filter order constants. Spring Security's WebFilterChainProxy runs at -100. */
    public static final class Order {
        public static final int CORRELATION = Ordered.HIGHEST_PRECEDENCE;
        public static final int ACCESS_LOG = Ordered.HIGHEST_PRECEDENCE + 1;
        public static final int HEADER_SANITIZER = Ordered.HIGHEST_PRECEDENCE + 5;
        public static final int PATH_GUARD = Ordered.HIGHEST_PRECEDENCE + 10;
        // after authentication (-100) so the rate limiter can key by JWT subject
        public static final int RATE_LIMIT = -60;
        public static final int REQUEST_SIZE = -50;

        private Order() {
        }
    }

    /** Accepts a well-formed inbound {@code X-Correlation-Id} or generates one; forwards it and echoes it on every response. */
    @Component
    public static class CorrelationIdWebFilter implements WebFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String inbound = exchange.getRequest().getHeaders().getFirst(CorrelationIds.HEADER);
            String id = CorrelationIds.isValid(inbound) ? inbound : CorrelationIds.newId();
            exchange.getAttributes().put(Problems.CORRELATION_ATTR, id);
            exchange.getAttributes().put(Problems.ORIGINAL_PATH_ATTR, exchange.getRequest().getPath().value());
            ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(CorrelationIds.HEADER, id)).build();
            // beforeCommit overwrites any value echoed by the upstream service (no duplicates)
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(CorrelationIds.HEADER, id);
                return Mono.empty();
            });
            return chain.filter(exchange.mutate().request(request).build());
        }

        @Override
        public int getOrder() {
            return Order.CORRELATION;
        }
    }

    /** One structured access-log line per request (no query string, no headers, no tokens). */
    @Component
    public static class AccessLogWebFilter implements WebFilter, Ordered {
        private static final Logger log = LoggerFactory.getLogger("oms.gateway.access");

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            long start = System.nanoTime();
            return chain.filter(exchange).doFinally(signal -> {
                if (!log.isInfoEnabled()) {
                    return;
                }
                Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                var status = exchange.getResponse().getStatusCode();
                String path = LogMasking.stripNewlines(exchange.getRequest().getPath().value());
                long ms = (System.nanoTime() - start) / 1_000_000;
                try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIds.MDC_KEY,
                        String.valueOf(Problems.correlationId(exchange)))) {
                    log.info("{} {} -> {} in {}ms route={} signal={}", exchange.getRequest().getMethod(), path,
                            status == null ? "-" : status.value(), ms, route == null ? "-" : route.getId(), signal);
                }
            });
        }

        @Override
        public int getOrder() {
            return Order.ACCESS_LOG;
        }
    }

    /**
     * Removes inbound headers a client must never be able to set: identity-style headers some backends or proxies
     * trust ({@code X-User-*}, {@code X-Roles}, ...), proxy headers ({@code Forwarded}, {@code X-Forwarded-*}, {@code X-Real-Ip};
     * the gateway adds its own), and internal-only headers. The gateway never injects identity headers: backends
     * authenticate from the forwarded {@code Authorization} bearer token. Hop-by-hop headers are removed by Spring Cloud
     * Gateway's built-in RemoveHopByHop filter.
     */
    @Component
    public static class InboundHeaderSanitizer implements WebFilter, Ordered {
        static final List<String> PREFIXES = List.of("x-user-", "x-auth-", "x-authenticated-", "x-forwarded-", "x-internal-",
                "x-service-", "x-gateway-", "x-oms-");
        static final List<String> EXACT = List.of("x-user", "x-roles", "x-role", "x-customer-id", "x-customerid",
                "x-real-ip", "forwarded", "x-original-uri", "x-original-url", "x-rewrite-url", "x-http-method-override");

        static boolean untrusted(String headerName) {
            String n = headerName.toLowerCase(Locale.ROOT);
            return EXACT.contains(n) || PREFIXES.stream().anyMatch(n::startsWith);
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            List<String> toRemove = new ArrayList<>();
            exchange.getRequest().getHeaders().keySet().forEach(h -> {
                if (untrusted(h)) {
                    toRemove.add(h);
                }
            });
            if (toRemove.isEmpty()) {
                return chain.filter(exchange);
            }
            ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> toRemove.forEach(h::remove)).build();
            return chain.filter(exchange.mutate().request(request).build());
        }

        @Override
        public int getOrder() {
            return Order.HEADER_SANITIZER;
        }
    }

    /**
     * Path hygiene before routing/authorization: rejects traversal, encoded separators, matrix params and double
     * encoding (400), and answers 404 for anything under {@code /api/v1/internal} (never routed, even for valid SERVICE tokens).
     */
    @Component
    public static class PathGuardWebFilter implements WebFilter, Ordered {
        private static final Pattern DOUBLE_ENCODED = Pattern.compile("%[0-9a-fA-F]{2}");
        private final Problems problems;

        public PathGuardWebFilter(Problems problems) {
            this.problems = problems;
        }

        /** Returns {@code null} when the path is acceptable, otherwise the rejecting status. */
        static HttpStatus check(String rawPath) {
            String lower = rawPath.toLowerCase(Locale.ROOT);
            if (rawPath.indexOf('\\') >= 0 || rawPath.indexOf(';') >= 0 || lower.contains("%2f") || lower.contains("%5c")
                    || lower.contains("%00") || lower.contains("%3b")) {
                return HttpStatus.BAD_REQUEST;
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return HttpStatus.BAD_REQUEST;
            }
            if (DOUBLE_ENCODED.matcher(decoded).find()) {
                return HttpStatus.BAD_REQUEST;
            }
            for (String segment : decoded.split("/", -1)) {
                if (segment.equals("..") || segment.equals(".")) {
                    return HttpStatus.BAD_REQUEST;
                }
            }
            for (int i = 0; i < decoded.length(); i++) {
                if (Character.isISOControl(decoded.charAt(i))) {
                    return HttpStatus.BAD_REQUEST;
                }
            }
            String normalized = decoded.toLowerCase(Locale.ROOT).replaceAll("/{2,}", "/");
            if (normalized.equals("/api/v1/internal") || normalized.startsWith("/api/v1/internal/")) {
                return HttpStatus.NOT_FOUND;
            }
            return null;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            HttpStatus rejected = check(exchange.getRequest().getURI().getRawPath());
            if (rejected == null) {
                return chain.filter(exchange);
            }
            return rejected == HttpStatus.NOT_FOUND
                    ? problems.write(exchange, rejected, "NOT_FOUND", "Not Found", "No such resource.")
                    : problems.write(exchange, rejected, "BAD_REQUEST", "Bad Request", "Malformed request path.");
        }

        @Override
        public int getOrder() {
            return Order.PATH_GUARD;
        }
    }
}
