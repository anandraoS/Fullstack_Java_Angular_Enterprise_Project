package com.enterprise.oms.gateway;

import com.enterprise.oms.common.security.JwtSecret;
import com.enterprise.oms.common.security.JwtTokenFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Shared test fixtures: stub upstreams, a per-run random JWT secret and token minting. No secret is hard-coded. */
public final class TestEnv {

    public static final String SECRET = JwtSecret.randomSecretString();
    /** Test-only constant demo password (contract: tests use a test constant). */
    public static final String DEMO_PASSWORD = "test-demo-password-123";
    public static final UUID CUSTOMER1 = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    public static final UUID CUSTOMER2 = UUID.fromString("c0000000-0000-0000-0000-000000000002");

    public static final StubServer ORDER = new StubServer();
    public static final StubServer INVENTORY = new StubServer();
    public static final StubServer FULFILLMENT = new StubServer();
    public static final StubServer NOTIFICATION = new StubServer();
    public static final List<StubServer> ALL = List.of(ORDER, INVENTORY, FULFILLMENT, NOTIFICATION);

    public static final JwtTokenFactory TOKENS = new JwtTokenFactory(JwtSecret.of(SECRET), "oms-dev-issuer", "oms-api");

    private TestEnv() {
    }

    public static void register(DynamicPropertyRegistry r) {
        r.add("oms.security.jwt.secret", () -> SECRET);
        r.add("oms.demo.password", () -> DEMO_PASSWORD);
        r.add("oms.gateway.routes.order.uri", ORDER::url);
        r.add("oms.gateway.routes.inventory.uri", INVENTORY::url);
        r.add("oms.gateway.routes.fulfillment.uri", FULFILLMENT::url);
        r.add("oms.gateway.routes.notification.uri", NOTIFICATION::url);
    }

    public static void resetStubs() {
        ALL.forEach(StubServer::reset);
    }

    /** A loopback port nothing listens on (connection refused). */
    public static int deadPort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String token(String sub, UUID customerId, String... roles) {
        return TOKENS.issue(sub, List.of(roles), customerId, Duration.ofMinutes(10));
    }

    public static String customer() {
        return token("customer1", CUSTOMER1, "CUSTOMER");
    }

    public static String operator() {
        return token("operator1", null, "OPERATOR");
    }

    public static String admin() {
        return token("admin1", null, "ADMIN");
    }

    public static WebTestClient client(int port) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(30)).build();
    }
}
