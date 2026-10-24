package com.enterprise.oms.common.web;

import com.enterprise.oms.common.error.IdempotencyKeyRequiredException;
import com.enterprise.oms.common.error.RequestValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Validation of the Idempotency-Key header and request fingerprinting. */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private IdempotencyKeys() {
    }

    /**
     * @return the trimmed key
     * @throws IdempotencyKeyRequiredException header absent/blank (400 IDEMPOTENCY_KEY_REQUIRED)
     * @throws RequestValidationException      format invalid (400 VALIDATION_FAILED)
     */
    public static String require(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        String key = headerValue.trim();
        if (!VALID.matcher(key).matches()) {
            throw new RequestValidationException(HEADER, "must be 8-64 characters of A-Z a-z 0-9 _ -");
        }
        return key;
    }

    /** SHA-256 hex of the (canonical) request body, to detect "same key, different body". */
    public static String fingerprint(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
