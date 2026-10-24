package com.enterprise.oms.common.audit;

import java.time.Instant;
import java.util.UUID;

/** API representation of an audit row. */
public record AuditEventView(UUID id, Instant occurredAt, String actor, String entityType, String entityId,
                             String action, String detail, String correlationId) {

    public static AuditEventView of(AuditEvent e) {
        return new AuditEventView(e.getId(), e.getOccurredAt(), e.getActor(), e.getEntityType(), e.getEntityId(),
                e.getAction(), e.getDetail(), e.getCorrelationId());
    }
}
