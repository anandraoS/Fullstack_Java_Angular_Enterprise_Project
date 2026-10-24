package com.enterprise.oms.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class NonDevProfileTest {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
    }

    @Test
    void tokenEndpointDoesNotExistOutsideProfileDev() {
        var body = Map.of("username", "admin1", "password", TestEnv.DEMO_PASSWORD);
        TestEnv.client(port).post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isUnauthorized();
        TestEnv.client(port).post().uri("/api/v1/auth/token").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.admin())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isNotFound();
    }
}
