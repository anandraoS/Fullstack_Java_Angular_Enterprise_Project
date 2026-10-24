package com.enterprise.oms.common.error;

/** 409 INVALID_STATE_TRANSITION. */
public class InvalidStateTransitionException extends OmsException {
    private final String from;
    private final String to;

    public InvalidStateTransitionException(String entity, Object from, Object to) {
        super(ErrorCode.INVALID_STATE_TRANSITION,
                entity + " cannot transition from " + from + " to " + to);
        this.from = String.valueOf(from);
        this.to = String.valueOf(to);
    }

    public String from() { return from; }
    public String to() { return to; }
}
