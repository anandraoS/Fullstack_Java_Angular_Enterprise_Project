package com.enterprise.oms.common.error;

/** 400 IDEMPOTENCY_KEY_REQUIRED. */
public class IdempotencyKeyRequiredException extends OmsException {
    public IdempotencyKeyRequiredException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Header Idempotency-Key is required (8-64 chars of A-Z a-z 0-9 _ -)");
    }
}
