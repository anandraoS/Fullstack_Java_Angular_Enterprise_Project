package com.enterprise.oms.gateway;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class TokenEndpointTest {

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.rate-limit.enabled", () -> "false");
        r.add("oms.gateway.throttle.max-failures", () -> "1000"); // throttling has its own test class
        r.add("oms.gateway.throttle.max-failures-per-ip", () -> "1000");
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    private WebTestClient.ResponseSpec login(String username, String password) {
        return client.post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password)).exchange();
    }

    @Test
    void issuesContractShapedTokensForAllFourDemoUsers() throws Exception {
        record Case(String user, List<String> roles, String customerId) {
        }
        for (Case c : List.of(
                new Case("customer1", List.of("CUSTOMER"), "c0000000-0000-0000-0000-000000000001"),
                new Case("customer2", List.of("CUSTOMER"), "c0000000-0000-0000-0000-000000000002"),
                new Case("operator1", List.of("OPERATOR"), null),
                new Case("admin1", List.of("ADMIN"), null))) {
            byte[] raw = login(c.user(), TestEnv.DEMO_PASSWORD).expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                    .expectBody()
                    .jsonPath("$.tokenType").isEqualTo("Bearer")
                    .jsonPath("$.expiresIn").isEqualTo(3600)
                    .jsonPath("$.username").isEqualTo(c.user())
                    .jsonPath("$.roles[0]").isEqualTo(c.roles().get(0))
                    .jsonPath("$.customerId").value(v -> assertThat(v).isEqualTo(c.customerId()))
                    .returnResult().getResponseBody();
            String access = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw).get("accessToken").asText();
            var claims = SignedJWT.parse(access).getJWTClaimsSet();
            assertThat(claims.getSubject()).isEqualTo(c.user());
            assertThat(claims.getIssuer()).isEqualTo("oms-dev-issuer");
            assertThat(claims.getAudience()).containsExactly("oms-api");
            assertThat(claims.getStringListClaim("roles")).isEqualTo(c.roles());
            assertThat(claims.getStringClaim("customerId")).isEqualTo(c.customerId());
            assertThat(claims.getJWTID()).isNotBlank();
            assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(3_600_000L);
        }
    }

    @Test
    void issuedTokenIsAcceptedByTheGatewayAndCarriesTheRoleLimits() throws Exception {
        byte[] raw = login("customer1", TestEnv.DEMO_PASSWORD).expectBody().returnResult().getResponseBody();
        String access = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw).get("accessToken").asText();
        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + access).exchange().expectStatus().isOk();
        assertThat(TestEnv.ORDER.last().header("authorization")).isEqualTo("Bearer " + access);
        client.get().uri("/api/v1/inventory").header(HttpHeaders.AUTHORIZATION, "Bearer " + access).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericProblem() {
        String wrongPw = new String(login("customer1", "definitely-wrong").expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED").returnResult().getResponseBody());
        String unknown = new String(login("nobody", TestEnv.DEMO_PASSWORD).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED").returnResult().getResponseBody());
        for (String body : List.of(wrongPw, unknown)) {
            assertThat(body).doesNotContain("accessToken").doesNotContain("definitely-wrong").doesNotContain(TestEnv.DEMO_PASSWORD);
        }
        assertThat(strip(wrongPw)).isEqualTo(strip(unknown));
    }

    private static String strip(String problem) {
        return problem.replaceAll("\"(timestamp|correlationId)\":\"[^\"]*\"", "");
    }

    @Test
    void validatesTheRequestBody() {
        client.post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"username\":\"customer1\"}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
        client.post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");
        client.post().uri("/api/v1/auth/token").contentType(MediaType.TEXT_PLAIN).bodyValue("username=a")
                .exchange().expectStatus().isEqualTo(415);
        client.post().uri("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "customer1", "password", "x".repeat(200)))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void onlyPostIsAccepted() {
        client.get().uri("/api/v1/auth/token").exchange().expectStatus().value(s -> assertThat(s).isIn(401, 405));
    }
}
