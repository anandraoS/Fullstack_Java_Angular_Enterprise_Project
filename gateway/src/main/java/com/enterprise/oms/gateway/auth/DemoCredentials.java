package com.enterprise.oms.gateway.auth;

import com.enterprise.oms.common.security.Roles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The four synthetic demo users (contract sections 4 and 9). All share the password from {@code OMS_DEMO_PASSWORD}
 * (property {@code oms.demo.password}); there is no default and the application FAILS TO START in profile {@code dev}
 * when it is unset or shorter than 8 characters. Only a SHA-256 digest is kept in memory and comparison is constant time.
 */
@Component
@Profile("dev")
public class DemoCredentials {

    public static final int MIN_PASSWORD_LENGTH = 8;

    public record DemoUser(String username, List<String> roles, UUID customerId) {
    }

    private static final Map<String, DemoUser> USERS = Map.of(
            "customer1", new DemoUser("customer1", List.of(Roles.CUSTOMER),
                    UUID.fromString("c0000000-0000-0000-0000-000000000001")),
            "customer2", new DemoUser("customer2", List.of(Roles.CUSTOMER),
                    UUID.fromString("c0000000-0000-0000-0000-000000000002")),
            "operator1", new DemoUser("operator1", List.of(Roles.OPERATOR), null),
            "admin1", new DemoUser("admin1", List.of(Roles.ADMIN), null));

    private final byte[] passwordDigest;

    public DemoCredentials(@Value("${oms.demo.password:}") String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("Profile 'dev' requires env OMS_DEMO_PASSWORD (property oms.demo.password) "
                    + "with at least " + MIN_PASSWORD_LENGTH + " characters; the dev token endpoint has no default password");
        }
        this.passwordDigest = sha256(password);
    }

    /**
     * Verifies the credentials in constant time with respect to the password content, and does the same amount of work for
     * unknown usernames (no user enumeration through timing or response).
     */
    public Optional<DemoUser> authenticate(String username, String password) {
        boolean passwordOk = MessageDigest.isEqual(passwordDigest, sha256(password == null ? "" : password));
        DemoUser user = username == null ? null : USERS.get(username);
        return passwordOk && user != null ? Optional.of(user) : Optional.empty();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
