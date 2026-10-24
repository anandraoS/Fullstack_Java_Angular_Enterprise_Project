package com.enterprise.oms.gateway.ops;

import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.gateway.config.GatewayProperties;
import com.enterprise.oms.gateway.problem.Problems;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/**
 * {@code GET /api/v1/ops/health} (OPERATOR/ADMIN, enforced by the security chain): probes each service's
 * {@code /actuator/health/readiness} in parallel with a short timeout and returns the contract section 5.5 shape.
 * Always HTTP 200; a failing service is reported in the body. Details are generic (no URLs or exception text).
 */
@RestController
public class OpsHealthController {

    private static final List<String> ORDER = List.of("order", "inventory", "fulfillment", "notification");

    private final Map<String, URI> targets = new LinkedHashMap<>();
    private final Duration timeout;
    private final WebClient client;

    public OpsHealthController(GatewayProperties props, WebClient.Builder builder) {
        GatewayProperties.Routes r = props.getRoutes();
        targets.put("order", r.getOrder().toUri());
        targets.put("inventory", r.getInventory().toUri());
        targets.put("fulfillment", r.getFulfillment().toUri());
        targets.put("notification", r.getNotification().toUri());
        this.timeout = props.getHealth().getTimeout();
        this.client = builder.build();
    }

    @GetMapping("/api/v1/ops/health")
    public Mono<Map<String, Object>> health(ServerWebExchange exchange) {
        String correlationId = Problems.correlationId(exchange);
        return Flux.fromIterable(ORDER)
                .flatMap(svc -> probe(svc, correlationId).map(result -> Map.entry(svc, result)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, TreeMap::new)
                .map(results -> {
                    Map<String, Object> services = new LinkedHashMap<>();
                    ORDER.forEach(s -> services.put(s, results.get(s)));
                    long up = results.values().stream().filter(v -> "UP".equals(v.get("status"))).count();
                    String overall = up == ORDER.size() ? "UP" : up == 0 ? "DOWN" : "DEGRADED";
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", overall);
                    body.put("services", services);
                    return body;
                });
    }

    private Mono<Map<String, Object>> probe(String svc, String correlationId) {
        URI base = targets.get(svc);
        return client.get()
                .uri(base.resolve("/actuator/health/readiness"))
                .headers(h -> {
                    if (correlationId != null) {
                        h.set(CorrelationIds.HEADER, correlationId);
                    }
                })
                .exchangeToMono(resp -> resp.releaseBody().thenReturn(result(resp.statusCode())))
                .timeout(timeout)
                .onErrorResume(e -> Mono.just(status("DOWN", e instanceof TimeoutException ? "timeout" : "unreachable")));
    }

    private static Map<String, Object> result(HttpStatusCode status) {
        return status.is2xxSuccessful() ? status("UP", null) : status("DOWN", "HTTP " + status.value());
    }

    private static Map<String, Object> status(String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }
}
