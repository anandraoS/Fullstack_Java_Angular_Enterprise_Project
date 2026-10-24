package com.enterprise.oms.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Registers the platform entity/repository packages (outbox, idempotency, audit) in addition to the service's
 * own packages, so a plain {@code @SpringBootApplication} service sees OutboxEvent/ProcessedEvent/AuditEvent and
 * their repositories without {@code @EntityScan}. Do NOT add {@code @EnableJpaRepositories}/{@code @EntityScan}
 * to a service unless you also list these packages.
 */
@AutoConfiguration(before = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@ConditionalOnClass(JpaRepository.class)
@AutoConfigurationPackage(basePackages = {
        "com.enterprise.oms.common.outbox",
        "com.enterprise.oms.common.idempotency",
        "com.enterprise.oms.common.audit"})
public class OmsPersistenceAutoConfiguration {
}
