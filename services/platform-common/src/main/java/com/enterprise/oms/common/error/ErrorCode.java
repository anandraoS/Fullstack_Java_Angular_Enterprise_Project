package com.enterprise.oms.common.error;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/** Machine-readable error codes of the problem+json contract (section 5). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused with a different request"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header required"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    // additions beyond the contract list (standard HTTP conditions)
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }

    /** {@code https://oms.example/problems/<code-kebab>} */
    public String typeUri() {
        return "https://oms.example/problems/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Best-effort code for a bare HTTP status (used for framework-raised errors). */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> UPSTREAM_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}
