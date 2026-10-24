package com.enterprise.oms.common.error;

/** 409 CONFLICT (e.g. duplicate unique value, optimistic-lock loss). */
public class ConflictException extends OmsException {
    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
