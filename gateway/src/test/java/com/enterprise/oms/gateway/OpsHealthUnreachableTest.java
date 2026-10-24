package com.enterprise.oms.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class OpsHealthUnreachableTest {

    static final int DEAD_PORT = TestEnv.deadPort();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.routes.fulfillment.uri", () -> "http://127.0.0.1:" + DEAD_PORT);
    }

    @Test
    void connectionRefusedIsReportedAsUnreachableWithoutLeakingTheTarget() {
        TestEnv.resetStubs();
        TestEnv.client(port).get().uri("/api/v1/ops/health").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.operator())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DEGRADED")
                .jsonPath("$.services.fulfillment.status").isEqualTo("DOWN")
                .jsonPath("$.services.fulfillment.detail").isEqualTo("unreachable")
                .consumeWith(r -> assertThat(new String(r.getResponseBody()))
                        .doesNotContain("127.0.0.1").doesNotContain(String.valueOf(DEAD_PORT)));
    }
}
