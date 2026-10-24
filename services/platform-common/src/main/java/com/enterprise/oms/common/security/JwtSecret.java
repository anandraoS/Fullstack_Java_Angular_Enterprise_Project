package com.enterprise.oms.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The HS256 signing secret. Resolution order: property {@code oms.security.jwt.secret}, env {@code OMS_JWT_SECRET},
 * then (only if {@code allow-ephemeral-secret=true}) a random in-memory secret. Fails fast when absent or shorter
 * than 32 bytes. The value is never logged.
 */
public final class JwtSecret {

    public static final int MIN_BYTES = 32;
    public static final String ENV_NAME = "OMS_JWT_SECRET";
    private static final Logger log = LoggerFactory.getLogger(JwtSecret.class);

    private final byte[] bytes;
    private final boolean ephemeral;

    private JwtSecret(byte[] bytes, boolean ephemeral) {
        this.bytes = bytes;
        this.ephemeral = ephemeral;
    }

    public static JwtSecret resolve(OmsSecurityProperties.Jwt props, Environment env) {
        String value = props.getSecret();
        if (value == null || value.isBlank()) {
            value = env.getProperty(ENV_NAME);
        }
        if (value == null || value.isBlank()) {
            if (props.isAllowEphemeralSecret()) {
                log.warn("No JWT secret configured: using a RANDOM EPHEMERAL secret "
                        + "(oms.security.jwt.allow-ephemeral-secret=true). Tokens are valid only inside this JVM. "
                        + "Never use this outside tests / single-process local runs.");
                return random();
            }
            throw new IllegalStateException("JWT secret missing: set env " + ENV_NAME + " (or property "
                    + "oms.security.jwt.secret) to a random value of at least " + MIN_BYTES + " bytes");
        }
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        if (b.length < MIN_BYTES) {
            throw new IllegalStateException("JWT secret too short: " + b.length + " bytes, minimum is " + MIN_BYTES);
        }
        return new JwtSecret(b, false);
    }

    /** Wraps explicit bytes (tests, tooling). Same minimum length rule. */
    public static JwtSecret of(String value) {
        byte[] b = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (b.length < MIN_BYTES) {
            throw new IllegalStateException("JWT secret too short: " + b.length + " bytes, minimum is " + MIN_BYTES);
        }
        return new JwtSecret(b, false);
    }

    /** Random 48-byte secret (base64 text form, so it is a valid UTF-8 secret string as well). */
    public static JwtSecret random() {
        return new JwtSecret(randomSecretString().getBytes(StandardCharsets.UTF_8), true);
    }

    public static String randomSecretString() {
        byte[] raw = new byte[48];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean ephemeral() {
        return ephemeral;
    }

    @Override
    public String toString() {
        return "JwtSecret[redacted," + bytes.length + " bytes]";
    }
}
