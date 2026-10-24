package com.enterprise.oms.aiexamples;

import java.util.ArrayList;
import java.util.List;

/**
 * Example 06: logging PII and credentials, plus log forging via CR/LF. The real helper is
 * {@code com.enterprise.oms.common.observability.LogMasking} (maskEmail, maskToken, sanitize, stripNewlines);
 * contract section 10 says never log tokens/passwords/emails in full (emails masked as {@code a***@domain}).
 * This example carries its own tiny masker so it stays dependency-free.
 */
public final class Ex06PiiLogging {

    private Ex06PiiLogging() { }

    public static final class LogSink {
        public final List<String> lines = new ArrayList<>();
        public void info(String line) { lines.add(line); }
        public String all() { return String.join("\n", lines); }
    }

    /** Flawed: the "helpful for debugging" log line. */
    public static final class FlawedOrderLogging {
        private final LogSink log;
        public FlawedOrderLogging(LogSink log) { this.log = log; }

        public void onCreate(String customerEmail, String authorizationHeader, String note) {
            log.info("Creating order for email=" + customerEmail + " auth=" + authorizationHeader + " note=" + note);
        }
    }

    /** Fixed: mask the email, never log credentials, neutralise line breaks in user-controlled text. */
    public static final class FixedOrderLogging {
        private final LogSink log;
        public FixedOrderLogging(LogSink log) { this.log = log; }

        public void onCreate(String customerEmail, String authorizationHeader, String note) {
            log.info("Creating order for email=" + maskEmail(customerEmail)
                    + " authPresent=" + (authorizationHeader != null && !authorizationHeader.isBlank())
                    + " note=" + stripNewlines(note));
        }
    }

    public static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String stripNewlines(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n]+", "_");
    }
}
