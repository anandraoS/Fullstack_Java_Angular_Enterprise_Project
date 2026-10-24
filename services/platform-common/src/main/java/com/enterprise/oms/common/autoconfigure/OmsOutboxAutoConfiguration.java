package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.observability.Metrics;
import com.enterprise.oms.common.outbox.OutboxEvent;
import com.enterprise.oms.common.outbox.OutboxEventRepository;
import com.enterprise.oms.common.outbox.OutboxProperties;
import com.enterprise.oms.common.outbox.OutboxRelay;
import com.enterprise.oms.common.outbox.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional outbox: {@code OutboxService} (append) + {@code OutboxRelay} (scheduled publisher). Toggle: {@code oms.outbox.enabled}. */
@AutoConfiguration(after = {OmsKafkaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class,
        TransactionAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnClass({OutboxEvent.class, KafkaTemplate.class})
@ConditionalOnProperty(prefix = "oms.outbox", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OmsOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxService outboxService(OutboxEventRepository repository, ObjectMapper mapper,
                                org.springframework.beans.factory.ObjectProvider<MeterRegistry> registry) {
        OutboxService service = new OutboxService(repository, mapper);
        MeterRegistry r = registry.getIfAvailable();
        if (r != null) {
            Metrics.gauge(r, "oms.outbox.pending", service, OutboxService::pendingCount);
        }
        return service;
    }

    @Bean
    @ConditionalOnBean(name = "omsKafkaTemplate")
    @ConditionalOnProperty(prefix = "oms.outbox", name = "relay-enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(OutboxEventRepository repository, OutboxService service,
                            @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> kafka,
                            PlatformTransactionManager tm, OutboxProperties props) {
        return new OutboxRelay(repository, service, kafka, new TransactionTemplate(tm), props);
    }
}
