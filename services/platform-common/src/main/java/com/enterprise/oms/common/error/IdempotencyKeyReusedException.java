package com.enterprise.oms.common.error;

/** 422 IDEMPOTENCY_KEY_REUSED: same key, different request body. */
public class IdempotencyKeyReusedException extends OmsException {
    public IdempotencyKeyReusedException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was already used with a different request body");
    }
}
