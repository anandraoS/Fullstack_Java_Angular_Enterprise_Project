package com.enterprise.oms.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Regression for integration defect "gateway sheds load at 26 concurrent requests": found by the live 30-parallel-order run
 * (tests/e2e/oversell-live.mjs), where one POST /orders answered 503 UPSTREAM_UNAVAILABLE (BulkheadFullException) because the
 * Spring Cloud CircuitBreaker default semaphore bulkhead admits only 25 concurrent calls per service. The limit is now an
 * explicit property (oms.gateway.bulkhead.*, default 200).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class BulkheadConcurrencyTest {

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.rate-limit.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    @Test
    void sixtyConcurrentRequestsToOneServiceAreAllServed() {
        TestEnv.ORDER.reply().delay(600); // keeps every call in flight long enough to overlap (library default would shed at 26)
        String token = TestEnv.operator();
        ExecutorService pool = Executors.newFixedThreadPool(60);
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> client.get().uri("/api/v1/orders/abc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                        .returnResult(String.class).getStatus().value(), pool));
            }
            List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
            assertThat(statuses).as("no request may be shed with 503 below the configured bulkhead").allMatch(s -> s == 200);
        } finally {
            pool.shutdownNow();
        }
    }
}
