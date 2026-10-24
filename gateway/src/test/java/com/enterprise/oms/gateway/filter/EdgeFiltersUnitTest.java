package com.enterprise.oms.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeFiltersUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/internal", "/api/v1/internal/", "/api/v1/internal/products/validate", "/API/V1/INTERNAL/x",
            "/api/v1//internal/x", "/api/v1/%69nternal/x"})
    void internalPathsAre404(String path) {
        assertThat(EdgeFilters.PathGuardWebFilter.check(path)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders/..%2finternal", "/api/v1/orders/%2e%2e/x", "/api/v1/orders/../x", "/api/v1/orders/./x",
            "/api/v1/orders/%252e", "/api/v1/orders/a%5Cb", "/api/v1/orders/a\\b", "/api/v1/orders;jsessionid=1", "/api/v1/a%00b",
            "/api/v1/a%0d%0ab", "/api/v1/a%zz"})
    void traversalAndSmugglingShapesAre400(String path) {
        assertThat(EdgeFilters.PathGuardWebFilter.check(path)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders", "/api/v1/orders/c0000000-0000-0000-0000-000000000001/history", "/api/v1/ops/order/messaging",
            "/api/v1/internals-are-not-special", "/actuator/health", "/api/v1/products/a%20b"})
    void normalPathsPass(String path) {
        assertThat(EdgeFilters.PathGuardWebFilter.check(path)).isNull();
    }

    @Test
    void untrustedHeaderClassification() {
        for (String h : new String[]{"X-User-Id", "x-user-roles", "X-Roles", "X-Customer-Id", "X-Forwarded-For", "Forwarded", "X-Real-Ip",
                "X-Internal-Anything", "X-Service-Token", "X-Gateway-Route", "X-HTTP-Method-Override"}) {
            assertThat(EdgeFilters.InboundHeaderSanitizer.untrusted(h)).as(h).isTrue();
        }
        for (String h : new String[]{"Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id", "Accept", "User-Agent"}) {
            assertThat(EdgeFilters.InboundHeaderSanitizer.untrusted(h)).as(h).isFalse();
        }
    }
}
