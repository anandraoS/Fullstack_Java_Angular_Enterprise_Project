package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.testsupport.JwtTestTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REAL REST client against a JDK HttpServer playing inventory-service: transport hygiene (service token,
 * correlation id), Resilience4j retry (transient only), non-retried client errors, timeouts, circuit breaker.
 * Separate Spring context (it must NOT use the stub), listeners/scheduling off.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "oms.http.read-timeout=1500ms",
        "resilience4j.circuitbreaker.instances.inventory.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.inventory.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=1s"})
class InventoryClientResilienceTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger FAIL_FIRST = new AtomicInteger();      // answer 503 for the first N requests
    private static final AtomicInteger ALWAYS_STATUS = new AtomicInteger();   // 0 = normal behaviour, else fixed HTTP status
    private static volatile long delayMs;
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_CORRELATION = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();
    private static final List<String> METHODS = new CopyOnWriteArrayList<>();

    private static HttpServer startServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", exchange -> {
                int n = HITS.incrementAndGet();
                METHODS.add(exchange.getRequestMethod());
                LAST_PATH.set(exchange.getRequestURI().getPath());
                LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                LAST_CORRELATION.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
                try (InputStream in = exchange.getRequestBody()) {
                    LAST_BODY.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                try {
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int status = ALWAYS_STATUS.get() != 0 ? ALWAYS_STATUS.get() : (n <= FAIL_FIRST.get() ? 503 : 200);
                byte[] body = status == 200
                        ? ("{\"valid\":true,\"unexpectedField\":1,\"lines\":[{\"productId\":\"" + PRODUCT + "\",\"sku\":\"SKU-1\","
                        + "\"name\":\"Widget\",\"unitPrice\":12.50,\"available\":9,\"active\":true,\"ok\":true,\"reason\":null}]}")
                        .getBytes(StandardCharsets.UTF_8)
                        : "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // client timed out and went away
                } finally {
                    exchange.close();
                }
            });
            // several handler threads: a deliberately slow response must not queue the following attempts behind it
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "fake-inventory");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final UUID PRODUCT = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
        registry.add("oms.inventory.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired InventoryClient client;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        HITS.set(0);
        FAIL_FIRST.set(0);
        ALWAYS_STATUS.set(0);
        delayMs = 0;
        METHODS.clear();
        breakers.circuitBreaker("inventory").reset();
    }

    private List<EventPayloads.QuantityItem> items() {
        return List.of(new EventPayloads.QuantityItem(PRODUCT, 2));
    }

    @Test
    void theRealClientIsWiredNotAStub() {
        assertThat(client).isInstanceOf(RestInventoryClient.class);
    }

    @Test
    void callsTheInternalEndpointWithAServiceTokenAndTheCorrelationId() {
        MDC.put("correlationId", "corr-resilience-1");
        try {
            InventoryClient.ValidationResult r = client.validate(items());

            assertThat(r.valid()).isTrue();
            assertThat(r.safeLines()).hasSize(1);
            assertThat(r.safeLines().get(0).unitPrice()).isEqualByComparingTo("12.50");
            assertThat(r.safeLines().get(0).sku()).isEqualTo("SKU-1");
        } finally {
            MDC.remove("correlationId");
        }
        assertThat(METHODS).containsExactly("POST");
        assertThat(LAST_PATH.get()).isEqualTo("/api/v1/internal/products/validate");
        assertThat(LAST_CORRELATION.get()).isEqualTo("corr-resilience-1");
        assertThat(LAST_BODY.get()).contains(PRODUCT.toString()).contains("\"quantity\":2");
        String auth = LAST_AUTH.get();
        assertThat(auth).startsWith("Bearer ");
        String payload = new String(Base64.getUrlDecoder().decode(auth.substring(7).split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"sub\":\"svc-order-service\"").contains("SERVICE");
    }

    @Test
    void transientFailuresAreRetriedAndTheCallSucceeds() {
        FAIL_FIRST.set(2);

        assertThat(client.validate(items()).valid()).isTrue();

        assertThat(HITS.get()).isEqualTo(3);     // 2 x 503, then 200
    }

    @Test
    void persistentFailureGivesUpAfterThreeAttemptsWithATransientException() {
        ALWAYS_STATUS.set(503);

        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryUnavailableException.class)
                .hasMessageContaining("503");

        assertThat(HITS.get()).isEqualTo(3);
    }

    @Test
    void clientErrorsAreNotRetried() {
        ALWAYS_STATUS.set(400);

        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryCallException.class)
                .hasMessageContaining("400");

        assertThat(HITS.get()).isEqualTo(1);
    }

    @Test
    void unauthorizedIsAConfigurationProblemNotARetry() {
        ALWAYS_STATUS.set(401);
        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryCallException.class);
        assertThat(HITS.get()).isEqualTo(1);
    }

    @Test
    void aSlowInventoryHitsTheReadTimeoutAndIsRetried() {
        delayMs = 2500;   // > oms.http.read-timeout (1500ms in this test; generous so a busy CI machine never times out a normal call)

        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryUnavailableException.class)
                .hasMessageContaining("not reachable");

        assertThat(HITS.get()).isEqualTo(3);
    }

    @Test
    void theCircuitOpensAfterRepeatedFailuresAndRejectsCallsWithoutHittingTheServer() {
        ALWAYS_STATUS.set(503);
        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryUnavailableException.class);   // 3 failures
        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(RuntimeException.class);                 // 4th opens it
        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = HITS.get();

        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(CallNotPermittedException.class);

        assertThat(HITS.get()).isEqualTo(before);       // fail fast: inventory is not called at all
    }

    @Test
    void theCircuitClosesAgainOnceInventoryRecovers() throws Exception {
        ALWAYS_STATUS.set(503);
        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryUnavailableException.class);
        assertThatThrownBy(() -> client.validate(items())).isInstanceOf(RuntimeException.class);
        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        ALWAYS_STATUS.set(0);
        Thread.sleep(1300);   // wait-duration-in-open-state = 1s -> half-open probes allowed

        for (int i = 0; i < 3; i++) {
            assertThat(client.validate(items()).valid()).isTrue();
        }
        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void clientErrorsDoNotCountAgainstTheCircuitBreaker() {
        ALWAYS_STATUS.set(400);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.validate(items())).isInstanceOf(InventoryCallException.class);
        }
        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(HITS.get()).isEqualTo(6);
    }
}
