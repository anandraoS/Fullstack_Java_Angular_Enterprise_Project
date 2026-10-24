package com.enterprise.oms.common.error;

import java.util.List;

/**
 * Base class of all expected, client-visible failures. Translated to problem+json by
 * {@code GlobalExceptionHandler} (status and code come from {@link ErrorCode}). The message is shown to clients:
 * never put SQL, stack traces or secrets in it.
 */
public class OmsException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> errors;

    public OmsException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public OmsException(ErrorCode code, String message, List<FieldError> errors) {
        super(message);
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public ErrorCode code() { return code; }
    public List<FieldError> errors() { return errors; }
}
