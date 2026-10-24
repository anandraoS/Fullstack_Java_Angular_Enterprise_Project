package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.audit.AuditEventRepository;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import com.enterprise.oms.common.retention.RetentionProperties;
import com.enterprise.oms.common.retention.RetentionPurgeJob;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Scheduled purge of processed_events / audit_events. Toggle: {@code oms.retention.enabled}. */
@AutoConfiguration(after = {JpaRepositoriesAutoConfiguration.class, TransactionAutoConfiguration.class})
@ConditionalOnBean({ProcessedEventRepository.class, AuditEventRepository.class})
@ConditionalOnProperty(prefix = "oms.retention", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(RetentionProperties.class)
@EnableScheduling
public class OmsRetentionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RetentionPurgeJob retentionPurgeJob(ProcessedEventRepository processed, AuditEventRepository audit,
                                        PlatformTransactionManager tm, RetentionProperties props) {
        return new RetentionPurgeJob(processed, audit, new TransactionTemplate(tm), props, Clock.systemUTC());
    }
}
