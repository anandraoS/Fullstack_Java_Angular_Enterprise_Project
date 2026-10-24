package com.enterprise.oms.common.error;

/** 404. Also use it to hide resources the caller may not see (other customers' orders) - never 403 there. */
public class NotFoundException extends OmsException {
    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String resource, Object id) {
        super(ErrorCode.NOT_FOUND, resource + " " + id + " not found");
    }
}
