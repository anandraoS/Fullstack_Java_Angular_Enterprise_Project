package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.audit.AuditEvent;
import com.enterprise.oms.common.audit.AuditEventRepository;
import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.audit.OpsAuditController;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Audit trail service + {@code GET /api/v1/ops/audit}. Toggle: {@code oms.audit.enabled}; endpoint: {@code oms.audit.ops-endpoint-enabled}. */
@AutoConfiguration(after = {JpaRepositoriesAutoConfiguration.class, OmsCoreAutoConfiguration.class})
@ConditionalOnClass(AuditEvent.class)
@ConditionalOnProperty(prefix = "oms.audit", name = "enabled", matchIfMissing = true)
public class OmsAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AuditService auditService(AuditEventRepository repository, ObjectMapper mapper) {
        return new AuditService(repository, mapper);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "oms.security", name = "enabled", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "oms.audit", name = "ops-endpoint-enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    OpsAuditController opsAuditController(AuditService audit, PageRequestFactory pages) {
        return new OpsAuditController(audit, pages);
    }
}
