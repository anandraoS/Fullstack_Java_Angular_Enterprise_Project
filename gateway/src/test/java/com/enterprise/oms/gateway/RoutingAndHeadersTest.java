package com.enterprise.oms.gateway;

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
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability // enables the Prometheus registry in tests
class RoutingAndHeadersTest {

    @LocalServerPort
    int port;
    WebTestClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestEnv.register(r);
    }

    @BeforeEach
    void setUp() {
        TestEnv.resetStubs();
        client = TestEnv.client(port);
    }

    private WebTestClient.RequestHeadersSpec<?> get(String path, String token) {
        return client.get().uri(URI.create("http://localhost:" + port + path)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    // ---- routing -------------------------------------------------------------------------------------------------

    @Test
    void routesEachPathFamilyToItsService() {
        String op = TestEnv.operator();
        get("/api/v1/orders/abc/history", op).exchange().expectStatus().isOk();
        get("/api/v1/admin/saga/abc", op).exchange().expectStatus().isOk();
        get("/api/v1/customers?q=acme&page=0&size=5", op).exchange().expectStatus().isOk();
        get("/api/v1/products?q=x", op).exchange().expectStatus().isOk();
        get("/api/v1/inventory/reservations", op).exchange().expectStatus().isOk();
        get("/api/v1/fulfillments?status=PACKED", op).exchange().expectStatus().isOk();
        get("/api/v1/notifications", op).exchange().expectStatus().isOk();

        assertThat(TestEnv.ORDER.requests()).extracting(StubServer.Recorded::rawPath)
                .containsExactly("/api/v1/orders/abc/history", "/api/v1/admin/saga/abc", "/api/v1/customers");
        assertThat(TestEnv.ORDER.requests().get(2).query()).isEqualTo("q=acme&page=0&size=5");
        assertThat(TestEnv.INVENTORY.requests()).extracting(StubServer.Recorded::rawPath)
                .containsExactly("/api/v1/products", "/api/v1/inventory/reservations");
        assertThat(TestEnv.FULFILLMENT.requests()).extracting(StubServer.Recorded::rawPath).containsExactly("/api/v1/fulfillments");
        assertThat(TestEnv.NOTIFICATION.requests()).extracting(StubServer.Recorded::rawPath).containsExactly("/api/v1/notifications");
    }

    @Test
    void opsPathsAreRewrittenPerService() {
        String op = TestEnv.operator();
        get("/api/v1/ops/order/messaging", op).exchange().expectStatus().isOk();
        get("/api/v1/ops/inventory/audit?entityType=Order&entityId=1&page=0&size=20", op).exchange().expectStatus().isOk();
        get("/api/v1/ops/fulfillment/messaging", op).exchange().expectStatus().isOk();
        get("/api/v1/ops/notification/audit", op).exchange().expectStatus().isOk();

        assertThat(TestEnv.ORDER.last().rawPath()).isEqualTo("/api/v1/ops/messaging");
        assertThat(TestEnv.INVENTORY.last().rawPath()).isEqualTo("/api/v1/ops/audit");
        assertThat(TestEnv.INVENTORY.last().query()).isEqualTo("entityType=Order&entityId=1&page=0&size=20");
        assertThat(TestEnv.FULFILLMENT.last().rawPath()).isEqualTo("/api/v1/ops/messaging");
        assertThat(TestEnv.NOTIFICATION.last().rawPath()).isEqualTo("/api/v1/ops/audit");
    }

    @Test
    void unroutedPathIsProblemJson404ForAuthenticatedAnd401ForAnonymous() {
        get("/api/v1/does-not-exist", TestEnv.operator()).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        client.get().uri("/api/v1/does-not-exist").exchange().expectStatus().isUnauthorized();
        ORDER_INVENTORY_UNTOUCHED();
    }

    private void ORDER_INVENTORY_UNTOUCHED() {
        assertThat(TestEnv.ALL).allMatch(s -> s.requests().isEmpty());
    }

    @Test
    void fallbackEndpointIsNotReachableFromOutside() {
        client.get().uri("/fallback/order").exchange().expectStatus().isUnauthorized();
        get("/fallback/order", TestEnv.admin()).exchange().expectStatus().isForbidden();
    }

    // ---- internal path block ---------------------------------------------------------------------------------------

    @Test
    void internalPathsAreNeverRoutedEvenWithServiceOrAdminTokens() {
        for (String token : List.of("", TestEnv.token("svc-order-service", null, "SERVICE"), TestEnv.admin())) {
            for (String path : List.of("/api/v1/internal/products/validate", "/api/v1/internal", "/api/v1//internal/x",
                    "/API/V1/Internal/products/validate", "/api/v1/internal/")) {
                WebTestClient.RequestHeadersSpec<?> req = client.post().uri(URI.create("http://localhost:" + port + path))
                        .contentType(MediaType.APPLICATION_JSON).bodyValue("{}");
                if (!token.isEmpty()) {
                    req = client.post().uri(URI.create("http://localhost:" + port + path))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}");
                }
                req.exchange().expectStatus().isNotFound()
                        .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
            }
        }
        ORDER_INVENTORY_UNTOUCHED();
    }

    @Test
    void traversalAndEncodedSeparatorsAreRejected() {
        for (String path : List.of("/api/v1/orders/..%2finternal/x", "/api/v1/orders/%2e%2e/internal/x",
                "/api/v1/orders/../internal/x", "/api/v1/orders/%252e%252e/x", "/api/v1/orders/a%5cb", "/api/v1/orders;x=1/abc",
                "/api/v1/orders/a%00b")) {
            get(path, TestEnv.operator()).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");
        }
        ORDER_INVENTORY_UNTOUCHED();
    }

    // ---- header handling -------------------------------------------------------------------------------------------

    @Test
    void forwardsAuthorizationAndBusinessHeadersButStripsUntrustedOnes() {
        String token = TestEnv.customer();
        client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "key-12345678")
                .header("X-User-Id", "attacker")
                .header("X-User-Roles", "ADMIN")
                .header("X-Roles", "ADMIN")
                .header("X-Customer-Id", "c0000000-0000-0000-0000-000000000002")
                .header("X-Forwarded-For", "6.6.6.6")
                .header("X-Forwarded-Host", "evil.example")
                .header("Forwarded", "for=6.6.6.6")
                .header("X-Internal-Call", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"c0000000-0000-0000-0000-000000000001\"}")
                .exchange().expectStatus().isOk();

        StubServer.Recorded seen = TestEnv.ORDER.last();
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.header("authorization")).isEqualTo("Bearer " + token);
        assertThat(seen.header("idempotency-key")).isEqualTo("key-12345678");
        assertThat(seen.bodyText()).contains("c0000000-0000-0000-0000-000000000001");
        for (String h : List.of("x-user-id", "x-user-roles", "x-roles", "x-customer-id", "x-forwarded-host", "forwarded",
                "x-internal-call")) {
            assertThat(seen.headers()).as(h).doesNotContainKey(h);
        }
        assertThat(seen.headerValues("x-forwarded-for")).noneMatch(v -> v.contains("6.6.6.6"));
    }

    @Test
    void upstreamResponseHeadersAndStatusPassThrough() {
        TestEnv.ORDER.reply().status(202).header("Location", "/api/v1/orders/1").header("Idempotent-Replay", "true")
                .body("{\"id\":\"1\"}");
        client.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/api/v1/orders/1")
                .expectHeader().valueEquals("Idempotent-Replay", "true")
                .expectBody().jsonPath("$.id").isEqualTo("1");
    }

    @Test
    void upstreamErrorResponsesAreNotReplacedByTheGateway() {
        TestEnv.ORDER.reply().status(409).body("{\"code\":\"CONFLICT\"}").header("Content-Type", "application/problem+json");
        get("/api/v1/orders/1", TestEnv.customer()).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("CONFLICT");
    }

    @Test
    void securityHeadersAreSetOnceEvenWhenTheUpstreamSetsThem() {
        TestEnv.ORDER.reply().header("X-Content-Type-Options", "nosniff");
        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().exists("Content-Security-Policy")
                .expectHeader().exists("Cache-Control");
        // problem responses carry them too
        client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
    }

    // ---- correlation id --------------------------------------------------------------------------------------------

    @Test
    void generatesCorrelationIdForwardsItAndEchoesIt() {
        String echoed = client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .exchange().expectStatus().isOk().returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(echoed).isNotNull();
        UUID.fromString(echoed);
        assertThat(TestEnv.ORDER.last().header("x-correlation-id")).isEqualTo(echoed);
    }

    @Test
    void acceptsValidInboundCorrelationIdAndOverwritesTheUpstreamEcho() {
        TestEnv.ORDER.reply().header("X-Correlation-Id", "upstream-made-this-up");
        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .header("X-Correlation-Id", "trace-abc.123_X")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "trace-abc.123_X"); // valueEquals fails on multiple values
        assertThat(TestEnv.ORDER.last().header("x-correlation-id")).isEqualTo("trace-abc.123_X");
    }

    @Test
    void replacesMalformedInboundCorrelationId() {
        String bad = "x".repeat(100);
        String echoed = client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .header("X-Correlation-Id", bad)
                .exchange().expectStatus().isOk().returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(echoed).isNotEqualTo(bad);
        UUID.fromString(echoed);
        assertThat(TestEnv.ORDER.last().header("x-correlation-id")).isEqualTo(echoed);
    }

    @Test
    void problemBodiesCarryTheSameCorrelationIdAsTheHeader() {
        client.get().uri("/api/v1/orders").header("X-Correlation-Id", "corr-401").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Correlation-Id", "corr-401")
                .expectBody().jsonPath("$.correlationId").isEqualTo("corr-401");
    }

    // ---- request size limit ----------------------------------------------------------------------------------------

    @Test
    void rejectsDeclaredOversizedBodyWith413() {
        client.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new byte[65537])
                .exchange().expectStatus().isEqualTo(413)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(TestEnv.ORDER.requests()).isEmpty();
    }

    @Test
    void acceptsBodyExactlyAtTheLimit() {
        byte[] body = new byte[65536];
        java.util.Arrays.fill(body, (byte) 'a');
        client.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        assertThat(TestEnv.ORDER.last().body()).hasSize(65536);
    }

    @Test
    void rejectsOversizedChunkedBodyWith413() {
        byte[] chunk = new byte[8192];
        client.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.range(0, 9).map(i -> chunk), byte[].class)
                .exchange().expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(TestEnv.ORDER.requests()).isEmpty();
    }

    @Test
    void forwardsSmallChunkedBodyIntact() {
        client.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestEnv.customer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just("{\"a\":", "1}").map(s -> s.getBytes(StandardCharsets.UTF_8)), byte[].class)
                .exchange().expectStatus().isOk();
        assertThat(TestEnv.ORDER.last().bodyText()).isEqualTo("{\"a\":1}");
    }

    // ---- actuator --------------------------------------------------------------------------------------------------

    @Test
    void actuatorHealthAndPrometheusAreAvailableAndOtherEndpointsAreNot() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).value(b -> assertThat(b).contains("jvm_memory_used_bytes"));
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }
}
