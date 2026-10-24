package com.enterprise.oms.gateway.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadConfigurationBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.function.Function;

/**
 * Static route table (contract section 5.5). Targets come from {@code oms.gateway.routes.*.uri}. Every route is wrapped in a
 * circuit breaker whose fallback answers 503 {@code UPSTREAM_UNAVAILABLE} problem+json. Timeouts: connect and response
 * timeouts are the netty client settings in application.yml (2s / 10s); the Resilience4j time limiter is only a backstop.
 * No automatic retries: POST /orders and friends are not safely repeatable at the edge.
 */
@Configuration(proxyBeanMethods = false)
public class RoutesConfig {

    @Bean
    RouteLocator omsRoutes(RouteLocatorBuilder builder, GatewayProperties props) {
        URI order = props.getRoutes().getOrder().toUri();
        URI inventory = props.getRoutes().getInventory().toUri();
        URI fulfillment = props.getRoutes().getFulfillment().toUri();
        URI notification = props.getRoutes().getNotification().toUri();

        return builder.routes()
                .route("order-service", r -> r.path(
                                "/api/v1/customers", "/api/v1/customers/**",
                                "/api/v1/orders", "/api/v1/orders/**",
                                "/api/v1/admin/orders/**", "/api/v1/admin/saga/**", "/api/v1/admin/reconciliation/**")
                        .filters(breaker("order")).uri(order))
                .route("inventory-service", r -> r.path(
                                "/api/v1/products", "/api/v1/products/**",
                                "/api/v1/inventory", "/api/v1/inventory/**")
                        .filters(breaker("inventory")).uri(inventory))
                .route("fulfillment-service", r -> r.path(
                                "/api/v1/fulfillments", "/api/v1/fulfillments/**", "/api/v1/admin/fulfillments/**")
                        .filters(breaker("fulfillment")).uri(fulfillment))
                .route("notification-service", r -> r.path(
                                "/api/v1/notifications", "/api/v1/notifications/**", "/api/v1/admin/notifications/**")
                        .filters(breaker("notification")).uri(notification))
                // /api/v1/ops/{svc}/** -> /api/v1/ops/** on that service
                .route("ops-order", r -> r.path("/api/v1/ops/order/**")
                        .filters(opsRewrite("order")).uri(order))
                .route("ops-inventory", r -> r.path("/api/v1/ops/inventory/**")
                        .filters(opsRewrite("inventory")).uri(inventory))
                .route("ops-fulfillment", r -> r.path("/api/v1/ops/fulfillment/**")
                        .filters(opsRewrite("fulfillment")).uri(fulfillment))
                .route("ops-notification", r -> r.path("/api/v1/ops/notification/**")
                        .filters(opsRewrite("notification")).uri(notification))
                .build();
    }

    private static Function<GatewayFilterSpec, org.springframework.cloud.gateway.route.builder.UriSpec> opsRewrite(String svc) {
        return f -> breaker(svc).apply(f.rewritePath("/api/v1/ops/" + svc + "(?<segment>/.*)?", "/api/v1/ops${segment}"));
    }

    private static Function<GatewayFilterSpec, org.springframework.cloud.gateway.route.builder.UriSpec> breaker(String svc) {
        return f -> f.circuitBreaker(c -> c.setName(svc).setFallbackUri("forward:/fallback/" + svc));
    }

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerDefaults(GatewayProperties props) {
        GatewayProperties.CircuitBreaker cb = props.getCircuitBreaker();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(cb.getSlidingWindowSize())
                        .minimumNumberOfCalls(cb.getMinimumCalls())
                        .failureRateThreshold(cb.getFailureRatePercent())
                        .waitDurationInOpenState(cb.getWaitInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(cb.getTimeLimit()).build())
                .build());
    }

    /** Replaces the library's hidden 25-concurrent-calls default with an explicit, configurable limit per downstream service. */
    @Bean
    Customizer<ReactiveResilience4jBulkheadProvider> bulkheadDefaults(GatewayProperties props) {
        GatewayProperties.Bulkhead b = props.getBulkhead();
        return provider -> provider.configureDefault(id -> new Resilience4jBulkheadConfigurationBuilder()
                .bulkheadConfig(BulkheadConfig.custom()
                        .maxConcurrentCalls(b.getMaxConcurrentCalls())
                        .maxWaitDuration(b.getMaxWait())
                        .build())
                .build());
    }
}
