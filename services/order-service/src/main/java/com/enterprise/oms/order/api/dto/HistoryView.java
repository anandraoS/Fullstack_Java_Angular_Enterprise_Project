package com.enterprise.oms.order.api.dto;

import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.OrderStatusHistory;

import java.time.Instant;

/** History item (contract 5.1): {fromStatus, toStatus, reason, actor, occurredAt}. */
public record HistoryView(OrderStatus fromStatus, OrderStatus toStatus, String reason, String actor,
                          Instant occurredAt) {

    public static HistoryView of(OrderStatusHistory h) {
        return new HistoryView(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getActor(), h.getOccurredAt());
    }
}
