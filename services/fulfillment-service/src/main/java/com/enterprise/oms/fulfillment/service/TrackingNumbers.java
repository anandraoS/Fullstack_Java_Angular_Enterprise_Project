package com.enterprise.oms.fulfillment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Deterministic tracking numbers: {@code OMS-} + first 12 upper-case hex chars of SHA-256(orderId) (same order, same number). */
public final class TrackingNumbers {

    public static final String PREFIX = "OMS-";

    private TrackingNumbers() {
    }

    public static String forOrder(UUID orderId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(orderId.toString().getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().withUpperCase().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
