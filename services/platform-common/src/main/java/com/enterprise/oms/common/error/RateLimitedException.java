package com.enterprise.oms.common.error;

/** 429 RATE_LIMITED. */
public class RateLimitedException extends OmsException {
    public RateLimitedException(String message) {
        super(ErrorCode.RATE_LIMITED, message);
    }
}
