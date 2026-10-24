package com.enterprise.oms.notification.sender;

/** Outcome of one send attempt. {@code error} must never contain the recipient address. */
public record SendResult(boolean success, String error) {

    public static SendResult ok() {
        return new SendResult(true, null);
    }

    public static SendResult failure(String error) {
        return new SendResult(false, error);
    }
}
