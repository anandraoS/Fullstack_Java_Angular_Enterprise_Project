package com.enterprise.oms.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class RateLimitTest {

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.rate-limit.capacity", () -> "3");
        r.add("oms.gateway.rate-limit.refill-per-second", () -> "0.2"); // one token per 5s
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    private WebTestClient.ResponseSpec call(String token) {
        return client.get().uri("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    @Test
    void overLimitRequestsGet429ProblemJsonWithRetryAfterAndOtherSubjectsAreUnaffected() {
        String alice = TestEnv.token("rl-alice", TestEnv.CUSTOMER1, "CUSTOMER");
        String bob = TestEnv.token("rl-bob", TestEnv.CUSTOMER2, "CUSTOMER");
        for (int i = 0; i < 3; i++) {
            call(alice).expectStatus().isOk().expectHeader().exists("X-RateLimit-Remaining");
        }
        call(alice).expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectHeader().value("Retry-After", v -> assertThat(Long.parseLong(v)).isBetween(1L, 10L))
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.correlationId").isNotEmpty();
        assertThat(TestEnv.INVENTORY.requests()).hasSize(3); // the 4th never reached the upstream
        call(bob).expectStatus().isOk();
    }

    @Test
    void unauthenticatedRequestsAreRejectedBeforeConsumingBuckets() {
        // unauthenticated requests are rejected by security (401) before consuming a subject bucket
        for (int i = 0; i < 6; i++) {
            client.get().uri("/api/v1/products").exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void actuatorProbesAreExempt() {
        for (int i = 0; i < 10; i++) {
            client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        }
    }
}
