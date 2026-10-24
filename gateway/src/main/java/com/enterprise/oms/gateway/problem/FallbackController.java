package com.enterprise.oms.gateway.problem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Target of the circuit-breaker {@code forward:/fallback/{svc}}: connect failure, timeout or open circuit -> 503
 * {@code UPSTREAM_UNAVAILABLE}. Only reachable through an internal forward (guarded by the breaker exception attribute;
 * direct external calls get 404 and are also denied by the security rules).
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);
    private final Problems problems;

    public FallbackController(Problems problems) {
        this.problems = problems;
    }

    @RequestMapping("/fallback/{service}")
    public Mono<Void> fallback(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (cause == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        log.warn("Upstream unavailable: {}", cause.getClass().getSimpleName());
        return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "Service Unavailable",
                "The upstream service is temporarily unavailable. Retry later.", Map.of("Retry-After", "5"));
    }
}
