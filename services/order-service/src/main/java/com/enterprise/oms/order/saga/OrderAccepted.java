package com.enterprise.oms.order.saga;

import java.util.UUID;

/** In-process event published inside the create-order transaction; handled only AFTER it commits. */
public record OrderAccepted(UUID orderId) {
}
