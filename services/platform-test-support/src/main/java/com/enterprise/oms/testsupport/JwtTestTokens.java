package com.enterprise.oms.testsupport;

import com.enterprise.oms.common.security.JwtSecret;
import com.enterprise.oms.common.security.JwtTokenFactory;
import com.enterprise.oms.common.security.Roles;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * JWT helper for tests. The HS256 secret is generated randomly once per JVM (never hard-coded); call
 * {@link #registerProperties(DynamicPropertyRegistry)} from a {@code @DynamicPropertySource} method so the service
 * under test validates tokens signed here:
 * <pre>{@code
 * @DynamicPropertySource
 * static void jwt(DynamicPropertyRegistry r) { JwtTestTokens.registerProperties(r); }
 * ...
 * mvc.perform(get("/api/v1/orders").header("Authorization", JwtTestTokens.customer(customerId)))
 * }</pre>
 */
public final class JwtTestTokens {

    public static final String ISSUER = "oms-dev-issuer";
    public static final String AUDIENCE = "oms-api";

    private static final String SECRET = JwtSecret.randomSecretString();
    private static final JwtTokenFactory FACTORY = new JwtTokenFactory(JwtSecret.of(SECRET), ISSUER, AUDIENCE);

    private JwtTestTokens() {
    }

    /** Sets {@code oms.security.jwt.secret} to this JVM's random secret. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("oms.security.jwt.secret", () -> SECRET);
    }

    /** Same secret as a string, for tests that build their own {@code JwtSecret}. */
    public static String secret() {
        return SECRET;
    }

    public static JwtTokenFactory factory() {
        return FACTORY;
    }

    /** Raw token (no "Bearer " prefix). */
    public static String token(String subject, List<String> roles, UUID customerId, Duration ttl) {
        return FACTORY.issue(subject, roles, customerId, ttl);
    }

    /** {@code "Bearer <token>"} ready for the Authorization header. */
    public static String bearer(String subject, List<String> roles, UUID customerId) {
        return "Bearer " + token(subject, roles, customerId, Duration.ofMinutes(10));
    }

    public static String customer(UUID customerId) {
        return bearer("customer-" + customerId, List.of(Roles.CUSTOMER), customerId);
    }

    public static String operator() {
        return bearer("operator-test", List.of(Roles.OPERATOR), null);
    }

    public static String admin() {
        return bearer("admin-test", List.of(Roles.ADMIN), null);
    }

    public static String service(String serviceName) {
        return bearer("svc-" + serviceName, List.of(Roles.SERVICE), null);
    }

    public static String expired() {
        return "Bearer " + token("expired-user", List.of(Roles.ADMIN), null, Duration.ofHours(-1));
    }

    /** A token signed with a DIFFERENT random secret (must be rejected). */
    public static String signedWithWrongSecret() {
        JwtTokenFactory other = new JwtTokenFactory(JwtSecret.random(), ISSUER, AUDIENCE);
        return "Bearer " + other.issue("intruder", List.of(Roles.ADMIN), null, Duration.ofMinutes(10));
    }

    /** A validly signed token with the wrong audience (must be rejected). */
    public static String wrongAudience() {
        return "Bearer " + FACTORY.withAudience("some-other-api")
                .issue("user", List.of(Roles.ADMIN), null, Duration.ofMinutes(10));
    }
}
