package com.enterprise.oms.gateway;

import com.enterprise.oms.common.security.JwtSecret;
import com.enterprise.oms.common.security.JwtTokenFactory;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class EdgeSecurityTest {

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
        r.add("oms.gateway.rate-limit.enabled", () -> "false"); // the matrix below sends many requests with few subjects
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    private void assertUnauthorized(String authorizationHeader) {
        WebTestClient.RequestHeadersSpec<?> req = client.get().uri("/api/v1/orders");
        if (authorizationHeader != null) {
            req = client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        req.exchange().expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.type").isEqualTo("https://oms.example/problems/unauthenticated")
                .jsonPath("$.correlationId").isNotEmpty()
                .jsonPath("$.errors").isArray();
        assertThat(TestEnv.ORDER.requests()).as("upstream must not be called").isEmpty();
    }

    // ---- authentication failures ------------------------------------------------------------------------------------

    @Test
    void missingTokenIs401ProblemJson() {
        assertUnauthorized(null);
    }

    @Test
    void garbageAndWrongSchemeAre401() {
        assertUnauthorized("Bearer not-a-jwt");
        assertUnauthorized("Bearer ");
        assertUnauthorized("Basic dXNlcjpwYXNz");
    }

    @Test
    void expiredTokenIs401() {
        String expired = TestEnv.TOKENS.issue("customer1", List.of("CUSTOMER"), TestEnv.CUSTOMER1, Duration.ofMinutes(-10));
        assertUnauthorized("Bearer " + expired);
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() {
        JwtTokenFactory forged = new JwtTokenFactory(JwtSecret.of(JwtSecret.randomSecretString()), "oms-dev-issuer", "oms-api");
        assertUnauthorized("Bearer " + forged.issue("admin1", List.of("ADMIN"), null, Duration.ofMinutes(10)));
    }

    @Test
    void wrongAudienceIs401() {
        assertUnauthorized("Bearer " + TestEnv.TOKENS.withAudience("some-other-api")
                .issue("admin1", List.of("ADMIN"), null, Duration.ofMinutes(10)));
    }

    @Test
    void wrongIssuerIs401() {
        assertUnauthorized("Bearer " + TestEnv.TOKENS.withIssuer("evil-issuer")
                .issue("admin1", List.of("ADMIN"), null, Duration.ofMinutes(10)));
    }

    @Test
    void unsignedAlgNoneTokenIs401() {
        String none = new PlainJWT(new JWTClaimsSet.Builder().subject("admin1").issuer("oms-dev-issuer").audience("oms-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).claim("roles", List.of("ADMIN")).build()).serialize();
        assertUnauthorized("Bearer " + none);
    }

    @Test
    void wrongHmacAlgorithmIs401() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims(Instant.now().plusSeconds(600), "admin1"));
        jwt.sign(new MACSigner(TestEnv.SECRET.getBytes()));
        assertUnauthorized("Bearer " + jwt.serialize());
    }

    @Test
    void tokenWithoutExpiryOrSubjectIs401() throws Exception {
        SignedJWT noExp = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(null, "admin1"));
        noExp.sign(new MACSigner(TestEnv.SECRET.getBytes()));
        assertUnauthorized("Bearer " + noExp.serialize());
        SignedJWT noSub = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(Instant.now().plusSeconds(600), null));
        noSub.sign(new MACSigner(TestEnv.SECRET.getBytes()));
        assertUnauthorized("Bearer " + noSub.serialize());
    }

    @Test
    void accessTokenInQueryStringIsIgnored() {
        client.get().uri("/api/v1/orders?access_token=" + TestEnv.admin()).exchange().expectStatus().isUnauthorized();
    }

    private static JWTClaimsSet claims(Instant exp, String sub) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer("oms-dev-issuer").audience("oms-api")
                .claim("roles", List.of("ADMIN")).jwtID(UUID.randomUUID().toString());
        if (exp != null) {
            b.expirationTime(Date.from(exp));
        }
        if (sub != null) {
            b.subject(sub);
        }
        return b.build();
    }

    // ---- role-based edge authorization ------------------------------------------------------------------------------

    static Stream<Arguments> matrix() {
        String id = "c0000000-0000-0000-0000-000000000001";
        List<Arguments> a = new java.util.ArrayList<>();
        // method, path, role, expected allowed
        Object[][] rows = {
                // CUSTOMER
                {"GET", "/api/v1/orders", "CUSTOMER", true}, {"POST", "/api/v1/orders", "CUSTOMER", true},
                {"POST", "/api/v1/orders/1/cancel", "CUSTOMER", true}, {"GET", "/api/v1/customers/" + id, "CUSTOMER", true},
                {"GET", "/api/v1/customers", "CUSTOMER", false}, {"POST", "/api/v1/customers", "CUSTOMER", false},
                {"GET", "/api/v1/admin/orders/failed", "CUSTOMER", false}, {"POST", "/api/v1/admin/orders/1/retry", "CUSTOMER", false},
                {"GET", "/api/v1/admin/saga/1", "CUSTOMER", false}, {"POST", "/api/v1/admin/reconciliation/run", "CUSTOMER", false},
                {"GET", "/api/v1/products", "CUSTOMER", true}, {"GET", "/api/v1/products/1", "CUSTOMER", true},
                {"POST", "/api/v1/products", "CUSTOMER", false}, {"PUT", "/api/v1/products/1", "CUSTOMER", false},
                {"DELETE", "/api/v1/products/1", "CUSTOMER", false}, {"GET", "/api/v1/inventory", "CUSTOMER", false},
                {"PUT", "/api/v1/inventory/1/stock", "CUSTOMER", false}, {"GET", "/api/v1/fulfillments", "CUSTOMER", false},
                {"GET", "/api/v1/notifications", "CUSTOMER", true}, {"POST", "/api/v1/admin/notifications/1/retry", "CUSTOMER", false},
                {"GET", "/api/v1/ops/order/messaging", "CUSTOMER", false}, {"GET", "/api/v1/ops/health", "CUSTOMER", false},
                // OPERATOR
                {"GET", "/api/v1/customers", "OPERATOR", true}, {"POST", "/api/v1/customers", "OPERATOR", true},
                {"GET", "/api/v1/admin/orders/failed", "OPERATOR", true}, {"POST", "/api/v1/admin/orders/1/retry", "OPERATOR", true},
                {"GET", "/api/v1/admin/saga/1", "OPERATOR", true}, {"POST", "/api/v1/admin/reconciliation/run", "OPERATOR", false},
                {"POST", "/api/v1/products", "OPERATOR", false}, {"GET", "/api/v1/products", "OPERATOR", true},
                {"GET", "/api/v1/inventory", "OPERATOR", true}, {"PUT", "/api/v1/inventory/1/stock", "OPERATOR", true},
                {"GET", "/api/v1/fulfillments", "OPERATOR", true}, {"POST", "/api/v1/admin/fulfillments/1/retry", "OPERATOR", true},
                {"POST", "/api/v1/admin/notifications/1/retry", "OPERATOR", true}, {"GET", "/api/v1/ops/inventory/audit", "OPERATOR", true},
                // ADMIN
                {"POST", "/api/v1/admin/reconciliation/run", "ADMIN", true}, {"POST", "/api/v1/products", "ADMIN", true},
                {"PUT", "/api/v1/products/1", "ADMIN", true}, {"DELETE", "/api/v1/products/1", "ADMIN", true},
                {"GET", "/api/v1/inventory", "ADMIN", true}, {"GET", "/api/v1/orders/1", "ADMIN", true},
                {"GET", "/api/v1/ops/notification/messaging", "ADMIN", true},
                // SERVICE tokens and unknown roles never pass the edge
                {"GET", "/api/v1/orders", "SERVICE", false}, {"GET", "/api/v1/products", "SERVICE", false},
                {"GET", "/api/v1/ops/order/messaging", "SERVICE", false}, {"GET", "/api/v1/orders", "SUPERUSER", false},
        };
        for (Object[] r : rows) {
            a.add(Arguments.of(r[0], r[1], r[2], r[3]));
        }
        return a.stream();
    }

    @ParameterizedTest(name = "{2} {0} {1} -> allowed={3}")
    @MethodSource("matrix")
    void roleMatrix(String method, String path, String role, boolean allowed) {
        String token = TestEnv.token(role.toLowerCase() + "-user", "CUSTOMER".equals(role) ? TestEnv.CUSTOMER1 : null, role);
        WebTestClient.RequestHeadersSpec<?> spec = client.method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}");
        if (allowed) {
            spec.exchange().expectStatus().value(s -> assertThat(s).as("status").isNotIn(401, 403));
        } else {
            spec.exchange().expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith("application/problem+json")
                    .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
            assertThat(TestEnv.ALL).allMatch(s -> s.requests().isEmpty());
        }
    }

    // ---- CORS -------------------------------------------------------------------------------------------------------

    @Test
    void crossOriginPreflightIsNotGrantedByDefault() {
        client.options().uri("/api/v1/orders")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        assertThat(TestEnv.ALL).allMatch(s -> s.requests().isEmpty());
    }

    @Test
    void healthEndpointsNeedNoToken() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
