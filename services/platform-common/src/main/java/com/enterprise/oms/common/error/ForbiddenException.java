package com.enterprise.oms.common.error;

/** 403 FORBIDDEN (authenticated but not allowed). */
public class ForbiddenException extends OmsException {
    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
