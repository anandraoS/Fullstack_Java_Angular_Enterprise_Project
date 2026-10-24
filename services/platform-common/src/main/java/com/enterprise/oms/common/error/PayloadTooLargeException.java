package com.enterprise.oms.common.error;

/** 413 PAYLOAD_TOO_LARGE. */
public class PayloadTooLargeException extends OmsException {
    public PayloadTooLargeException(long maxBytes) {
        super(ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds the maximum of " + maxBytes + " bytes");
    }
}
