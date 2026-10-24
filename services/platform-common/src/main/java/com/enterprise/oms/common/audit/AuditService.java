package com.enterprise.oms.common.audit;

import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.common.web.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Writes and queries the audit trail. {@code record} joins the caller's transaction (REQUIRED). */
public class AuditService {

    public static final String SYSTEM_ACTOR = "system";
    private static final int MAX_DETAIL = 8000;

    private final AuditEventRepository repository;
    private final ObjectMapper mapper;

    public AuditService(AuditEventRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Records an audit entry. Actor = authenticated principal name (JWT subject) or {@code system};
     * correlationId from MDC.
     *
     * @param detail a String stored as-is, or any object serialized to JSON (null allowed). Never pass secrets.
     */
    @Transactional
    public AuditEvent record(String entityType, Object entityId, String action, Object detail) {
        return record(currentActor(), entityType, entityId, action, detail);
    }

    /** Same as above with an explicit actor (e.g. a scheduler or event consumer). */
    @Transactional
    public AuditEvent record(String actor, String entityType, Object entityId, String action, Object detail) {
        String text = detail == null ? null : detail instanceof String s ? s : EventJson.toJson(mapper, detail);
        if (text != null && text.length() > MAX_DETAIL) {
            text = text.substring(0, MAX_DETAIL);
        }
        AuditEvent event = new AuditEvent(UUID.randomUUID(), Instant.now(), actor, entityType,
                String.valueOf(entityId), action, text, CorrelationIds.current());
        return repository.save(event);
    }

    /** Filters are optional (null = no filter). */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> search(String entityType, String entityId, Pageable pageable) {
        Specification<AuditEvent> spec = Specification.where(null);
        if (entityType != null && !entityType.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null && !entityId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        Page<AuditEvent> page = repository.findAll(spec, pageable);
        return PageResponse.from(page, AuditEventView::of);
    }

    static String currentActor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getName() != null && !"anonymousUser".equals(a.getName())
                ? a.getName() : SYSTEM_ACTOR;
    }
}
