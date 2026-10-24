package com.enterprise.oms.gateway;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Brute-force throttling of the dev token endpoint (per ip+username lock, then per-ip lock). Methods share state, hence ordered. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TokenThrottleTest {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.rate-limit.enabled", () -> "false");
        r.add("oms.gateway.throttle.max-failures", () -> "5");
        r.add("oms.gateway.throttle.max-failures-per-ip", () -> "8");
        r.add("oms.gateway.throttle.lockout", () -> "10m");
    }

    private WebTestClient.ResponseSpec login(String username, String password) {
        return TestEnv.client(port).post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password)).exchange();
    }

    @Test
    @Order(1)
    void repeatedFailuresLockTheIpUsernamePairEvenForTheCorrectPassword() {
        for (int i = 0; i < 5; i++) {
            login("admin1", "wrong-" + i).expectStatus().isUnauthorized();
        }
        login("admin1", TestEnv.DEMO_PASSWORD).expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectHeader().value("Retry-After", v -> assertThat(Long.parseLong(v)).isBetween(1L, 600L))
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.accessToken").doesNotExist();
        // another username from the same ip is still served (the per-ip budget is not exhausted yet)
        login("customer1", TestEnv.DEMO_PASSWORD).expectStatus().isOk();
    }

    @Test
    @Order(2)
    void sprayingManyUsernamesLocksTheWholeIp() {
        for (int i = 0; i < 3; i++) { // 5 failures already counted for this ip + 3 = per-ip limit of 8
            login("spray" + i, "wrong").expectStatus().isUnauthorized();
        }
        login("customer1", TestEnv.DEMO_PASSWORD).expectStatus().isEqualTo(429).expectHeader().exists("Retry-After");
    }
}
