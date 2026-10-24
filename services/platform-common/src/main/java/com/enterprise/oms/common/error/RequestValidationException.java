package com.enterprise.oms.common.error;

import java.util.List;

/** 400 VALIDATION_FAILED with field errors (for checks done in code rather than Bean Validation). */
public class RequestValidationException extends OmsException {
    public RequestValidationException(String message, List<FieldError> errors) {
        super(ErrorCode.VALIDATION_FAILED, message, errors);
    }

    public RequestValidationException(String field, String message) {
        super(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(new FieldError(field, message)));
    }
}
