package com.enterprise.oms.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ResilienceTest {

    static final int DEAD_PORT = TestEnv.deadPort();

    @LocalServerPort
    int port;
    @Autowired
    ReactiveResilience4JCircuitBreakerFactory breakers;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.routes.notification.uri", () -> "http://127.0.0.1:" + DEAD_PORT);
        r.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "500ms");
        r.add("oms.gateway.circuit-breaker.time-limit", () -> "5s");
        r.add("oms.gateway.circuit-breaker.sliding-window-size", () -> "2");
        r.add("oms.gateway.circuit-breaker.minimum-calls", () -> "2");
        r.add("oms.gateway.circuit-breaker.failure-rate-percent", () -> "50");
        r.add("oms.gateway.circuit-breaker.wait-in-open-state", () -> "30s");
        r.add("oms.gateway.rate-limit.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    private WebTestClient.ResponseSpec call(String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.operator()).exchange();
    }

    private void assertUpstreamUnavailable(WebTestClient.ResponseSpec spec) {
        spec.expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.type").isEqualTo("https://oms.example/problems/upstream-unavailable")
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void connectionRefusedYields503Fallback() {
        assertUpstreamUnavailable(call("/api/v1/notifications"));
        call("/api/v1/notifications").expectBody().jsonPath("$.instance").isEqualTo("/api/v1/notifications");
    }

    @Test
    void responseTimeoutYields503FallbackAndThenTheCircuitOpensAndShortCircuits() {
        TestEnv.INVENTORY.reply().delay(1500);
        long t0 = System.nanoTime();
        assertUpstreamUnavailable(call("/api/v1/products"));
        assertThat((System.nanoTime() - t0) / 1_000_000).as("response timeout is 500ms, not the stub delay").isLessThan(1400);
        assertUpstreamUnavailable(call("/api/v1/products"));

        CircuitBreaker cb = breakers.getCircuitBreakerRegistry().circuitBreaker("inventory");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // open circuit: answered immediately by the fallback, upstream no longer called
        int before = TestEnv.INVENTORY.requests().size();
        long t1 = System.nanoTime();
        assertUpstreamUnavailable(call("/api/v1/products"));
        assertThat((System.nanoTime() - t1) / 1_000_000).isLessThan(300);
        assertThat(TestEnv.INVENTORY.requests()).hasSize(before);
        // other services are unaffected (separate breaker instances)
        call("/api/v1/orders").expectStatus().isOk();
    }

    @Test
    void upstream500IsPassedThroughNotMaskedAsUnavailable() {
        TestEnv.ORDER.reply().status(500).body("{\"code\":\"INTERNAL_ERROR\"}");
        call("/api/v1/orders").expectStatus().isEqualTo(500).expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR");
    }
}
