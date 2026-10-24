package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.idempotency.IdempotentEventProcessor;
import com.enterprise.oms.common.idempotency.ProcessedEvent;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import com.enterprise.oms.common.messaging.EventRouterFactory;
import com.enterprise.oms.common.messaging.KafkaConsumerConfigSupport;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.common.messaging.MessagingProperties;
import com.enterprise.oms.common.messaging.MessagingStatusService;
import com.enterprise.oms.common.messaging.OpsMessagingController;
import com.enterprise.oms.common.outbox.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Consumer side: idempotent processor, router factory, container factory
 * {@code omsKafkaListenerContainerFactory}, metrics, ops status service/endpoint. Toggle: {@code oms.messaging.enabled}.
 */
@AutoConfiguration(after = {OmsKafkaAutoConfiguration.class, OmsOutboxAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class})
@ConditionalOnClass({KafkaTemplate.class, ProcessedEvent.class})
@ConditionalOnProperty(prefix = "oms.messaging", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MessagingProperties.class)
public class OmsMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MessagingMetrics messagingMetrics(MeterRegistry registry, MessagingProperties props) {
        MessagingMetrics metrics = new MessagingMetrics(registry);
        metrics.preRegister(props.getTopics());
        return metrics;
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentEventProcessor idempotentEventProcessor(ProcessedEventRepository repository, MessagingMetrics metrics) {
        return new IdempotentEventProcessor(repository, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    EventRouterFactory eventRouterFactory(IdempotentEventProcessor processor, ObjectMapper mapper,
                                          MessagingMetrics metrics) {
        return new EventRouterFactory(processor, mapper, metrics);
    }

    @Bean
    @ConditionalOnBean(name = "omsKafkaTemplate")
    @ConditionalOnMissingBean
    KafkaConsumerConfigSupport kafkaConsumerConfigSupport(KafkaProperties kafkaProperties,
                                                          @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> dltTemplate,
                                                          MessagingMetrics metrics, MessagingProperties props,
                                                          ObjectProvider<MeterRegistry> meterRegistry) {
        KafkaConsumerConfigSupport support = new KafkaConsumerConfigSupport(kafkaProperties, dltTemplate, metrics, props);
        support.setMeterRegistry(meterRegistry.getIfAvailable());
        return support;
    }

    /** Use as {@code containerFactory = "omsKafkaListenerContainerFactory"} on {@code @KafkaListener}. */
    @Bean(name = "omsKafkaListenerContainerFactory")
    @ConditionalOnBean(KafkaConsumerConfigSupport.class)
    @ConditionalOnMissingBean(name = "omsKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> omsKafkaListenerContainerFactory(
            KafkaConsumerConfigSupport support, MessagingProperties props,
            @Value("${spring.application.name:oms-service}") String appName) {
        return support.containerFactory(props.getConsumerGroup() != null ? props.getConsumerGroup() : appName);
    }

    @Bean
    @ConditionalOnMissingBean
    MessagingStatusService messagingStatusService(@Value("${spring.application.name:oms-service}") String appName,
                                                  MessagingProperties props, KafkaProperties kafkaProperties,
                                                  MessagingMetrics metrics, ObjectProvider<OutboxService> outbox) {
        return new MessagingStatusService(appName, props, kafkaProperties, metrics, outbox);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "oms.security", name = "enabled", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "oms.messaging", name = "ops-endpoint-enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    OpsMessagingController opsMessagingController(MessagingStatusService service) {
        return new OpsMessagingController(service);
    }
}
