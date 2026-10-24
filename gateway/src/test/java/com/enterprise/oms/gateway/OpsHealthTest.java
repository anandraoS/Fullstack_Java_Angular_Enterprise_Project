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
class OpsHealthTest {

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.health.timeout", () -> "600ms");
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    private WebTestClient.ResponseSpec health(String token) {
        return client.get().uri("/api/v1/ops/health").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    @Test
    void allUp() {
        health(TestEnv.operator()).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.services.order.status").isEqualTo("UP")
                .jsonPath("$.services.inventory.status").isEqualTo("UP")
                .jsonPath("$.services.fulfillment.status").isEqualTo("UP")
                .jsonPath("$.services.notification.status").isEqualTo("UP")
                .jsonPath("$.services.order.detail").isEmpty();
    }

    @Test
    void oneServiceDownIsDegradedAndStillHttp200() {
        TestEnv.INVENTORY.readiness().status(503).body("{\"status\":\"DOWN\"}");
        health(TestEnv.admin()).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DEGRADED")
                .jsonPath("$.services.inventory.status").isEqualTo("DOWN")
                .jsonPath("$.services.inventory.detail").isEqualTo("HTTP 503")
                .jsonPath("$.services.order.status").isEqualTo("UP");
    }

    @Test
    void slowServiceIsReportedAsTimeoutWithinTheProbeBudget() {
        TestEnv.NOTIFICATION.readiness().delay(3000);
        long t0 = System.nanoTime();
        health(TestEnv.operator()).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DEGRADED")
                .jsonPath("$.services.notification.status").isEqualTo("DOWN")
                .jsonPath("$.services.notification.detail").isEqualTo("timeout");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2500);
    }

    @Test
    void allDownIsDown() {
        TestEnv.ALL.forEach(s -> s.readiness().status(503));
        health(TestEnv.operator()).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("DOWN");
    }

    @Test
    void rolesAreEnforced() {
        health(TestEnv.customer()).expectStatus().isForbidden();
        client.get().uri("/api/v1/ops/health").exchange().expectStatus().isUnauthorized();
    }
}
