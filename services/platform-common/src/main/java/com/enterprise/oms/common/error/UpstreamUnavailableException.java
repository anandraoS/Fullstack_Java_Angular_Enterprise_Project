package com.enterprise.oms.common.error;

/** 503 UPSTREAM_UNAVAILABLE (dependency down, circuit open, timeout). */
public class UpstreamUnavailableException extends OmsException {
    public UpstreamUnavailableException(String message) {
        super(ErrorCode.UPSTREAM_UNAVAILABLE, message);
    }
}
