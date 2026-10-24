package com.enterprise.oms.common.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers to keep PII and credentials out of logs. */
public final class LogMasking {

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|secret|token|authorization|api[-_]?key)\\s*[=:]\\s*)[^\\s,;&\"']+");

    private LogMasking() {
    }

    /** {@code alice@example.com} becomes {@code a***@example.com}. */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        Matcher m = EMAIL.matcher(email);
        return m.replaceAll("$1***@$2");
    }

    /** Keeps at most the first 4 characters: {@code abcd***}. Null-safe. */
    public static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        return token.substring(0, Math.min(4, token.length() / 2)) + "***";
    }

    /** Masks emails, bearer tokens, raw JWTs and {@code password=...}-style pairs in free text. */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String s = JWT.matcher(text).replaceAll("***jwt***");
        s = BEARER.matcher(s).replaceAll("$1***");
        s = KEY_VALUE_SECRET.matcher(s).replaceAll("$1***");
        return maskEmail(s);
    }

    /** Strips CR/LF to prevent log forging when logging user-controlled values. */
    public static String stripNewlines(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }
}
