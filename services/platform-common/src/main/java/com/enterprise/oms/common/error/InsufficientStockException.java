package com.enterprise.oms.common.error;

import java.util.List;
import java.util.UUID;

/** 409 INSUFFICIENT_STOCK; one {@code errors[]} entry per short product. */
public class InsufficientStockException extends OmsException {

    public record Shortage(UUID productId, int requested, int available) {
    }

    private final List<Shortage> shortages;

    public InsufficientStockException(List<Shortage> shortages) {
        super(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for " + shortages.size() + " product(s)",
                shortages.stream().map(s -> new FieldError("items[" + s.productId() + "]",
                        "requested " + s.requested() + ", available " + s.available())).toList());
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> shortages() { return shortages; }
}
