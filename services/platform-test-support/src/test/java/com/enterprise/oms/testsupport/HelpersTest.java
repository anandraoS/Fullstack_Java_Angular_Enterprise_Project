package com.enterprise.oms.testsupport;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpersTest {

    @Test
    void jwtTokensAreSignedWithTheRuntimeSecretAndCarryContractClaims() throws Exception {
        assertThat(JwtTestTokens.secret().getBytes().length).isGreaterThanOrEqualTo(32);
        UUID cust = UUID.randomUUID();
        String bearer = JwtTestTokens.customer(cust);
        assertThat(bearer).startsWith("Bearer ");
        SignedJWT jwt = SignedJWT.parse(bearer.substring(7));
        assertThat(jwt.verify(new MACVerifier(JwtTestTokens.secret()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("oms-dev-issuer");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("oms-api");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("customerId")).isEqualTo(cust.toString());
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(new Date());

        SignedJWT expired = SignedJWT.parse(JwtTestTokens.expired().substring(7));
        assertThat(expired.getJWTClaimsSet().getExpirationTime()).isBefore(new Date());
        SignedJWT wrong = SignedJWT.parse(JwtTestTokens.signedWithWrongSecret().substring(7));
        assertThat(wrong.verify(new MACVerifier(JwtTestTokens.secret()))).isFalse();
        assertThat(SignedJWT.parse(JwtTestTokens.service("order-service").substring(7)).getJWTClaimsSet()
                .getSubject()).isEqualTo("svc-order-service");
        assertThat(JwtTestTokens.token("x", List.of("ADMIN"), null, Duration.ofMinutes(1))).isNotBlank();
    }

    @Test
    void awaitsRetryUntilConditionHolds() {
        AtomicInteger n = new AtomicInteger();
        Awaits.until(() -> n.incrementAndGet() >= 3);
        assertThat(n.get()).isGreaterThanOrEqualTo(3);
        AtomicInteger m = new AtomicInteger();
        Awaits.untilAsserted(() -> assertThat(m.incrementAndGet()).isGreaterThanOrEqualTo(2));
        Awaits.neverWithin(Duration.ofMillis(300), () -> false);
        assertThatThrownBy(() -> Awaits.neverWithin(Duration.ofMillis(300), () -> true)).isInstanceOf(Exception.class);
    }

    @Test
    void jsonAssertionsCoverProblemAndPageShapes() {
        String problem = "{\"type\":\"https://oms.example/problems/not-found\",\"title\":\"Resource not found\","
                + "\"status\":404,\"code\":\"NOT_FOUND\",\"correlationId\":\"c\",\"timestamp\":\"2025-01-01T00:00:00Z\","
                + "\"errors\":[{\"field\":\"id\",\"message\":\"m\"}]}";
        JsonAssertions.of(problem).isProblem(404, "NOT_FOUND").hasText("/errors/0/field", "id").arraySize("/errors", 1)
                .hasNoField("/stackTrace");
        assertThatThrownBy(() -> JsonAssertions.of(problem).isProblem(400, "NOT_FOUND")).isInstanceOf(AssertionError.class);

        String page = "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"x\":null}";
        JsonAssertions.of(page).isPage(20, 0).hasInt("/totalElements", 0).isNull("/x");
        assertThatThrownBy(() -> JsonAssertions.of("{oops")).isInstanceOf(AssertionError.class);
    }
}
