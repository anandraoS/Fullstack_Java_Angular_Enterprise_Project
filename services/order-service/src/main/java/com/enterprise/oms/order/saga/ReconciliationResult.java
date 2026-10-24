package com.enterprise.oms.order.saga;

import java.util.List;

/** Response of {@code POST /api/v1/admin/reconciliation/run}. */
public record ReconciliationResult(int examined, int repaired, List<String> details) {
}
