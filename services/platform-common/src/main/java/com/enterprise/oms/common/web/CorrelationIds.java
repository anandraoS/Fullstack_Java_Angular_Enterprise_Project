package com.enterprise.oms.common.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation id constants and helpers (header {@code X-Correlation-Id}, MDC key {@code correlationId}). */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    /** Kafka header name (contract section 7). */
    public static final String KAFKA_HEADER = "correlationId";
    public static final String MDC_KEY = "correlationId";

    /** Accepted inbound format; anything else is replaced (prevents log injection / header abuse). */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationIds() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** The id bound to the current thread, or {@code null}. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Current id, creating and binding a new one if none. */
    public static String currentOrCreate() {
        String id = MDC.get(MDC_KEY);
        if (id == null) {
            id = newId();
            MDC.put(MDC_KEY, id);
        }
        return id;
    }
}
