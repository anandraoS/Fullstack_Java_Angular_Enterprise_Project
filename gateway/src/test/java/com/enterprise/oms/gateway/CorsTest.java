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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class CorsTest {

    static final String ORIGIN = "http://localhost:4200";

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.cors.allowed-origins", () -> ORIGIN);
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    @Test
    void preflightFromAllowedOriginIsAnsweredAtTheEdgeWithoutAuthentication() {
        client.options().uri("/api/v1/orders")
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,idempotency-key,content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGIN)
                .expectHeader().exists("Access-Control-Allow-Methods");
        assertThat(TestEnv.ALL).allMatch(s -> s.requests().isEmpty());
    }

    @Test
    void actualResponseExposesTheContractHeaders() {
        client.get().uri("/api/v1/orders").header("Origin", ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ORIGIN)
                .expectHeader().value("Access-Control-Expose-Headers", v -> assertThat(v)
                        .contains("Idempotent-Replay", "Location", "X-Correlation-Id", "Retry-After"));
    }

    @Test
    void otherOriginsAreRejected() {
        client.options().uri("/api/v1/orders")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void wildcardOriginsFailStartup() {
        assertThatThrownBy(() -> StartupConfigurationTest.run("dev", "oms.security.jwt.secret=" + TestEnv.SECRET,
                "oms.demo.password=" + TestEnv.DEMO_PASSWORD, "oms.gateway.cors.allowed-origins=*"))
                .hasStackTraceContaining("wildcards are not allowed");
    }
}
