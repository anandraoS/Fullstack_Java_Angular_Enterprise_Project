package com.enterprise.oms.common.autoconfigure;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates {@code omsKafkaTemplate}: a {@code KafkaTemplate<String,String>} with a reliable producer
 * (acks=all, enable.idempotence=true, max.in.flight<=5, bounded blocking). Used by the outbox relay and by the
 * dead-letter recoverer. Always inject it with {@code @Qualifier("omsKafkaTemplate")}.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "oms.kafka", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties({OmsKafkaProperties.class, KafkaProperties.class})
public class OmsKafkaAutoConfiguration {

    @Bean(name = "omsKafkaTemplate")
    @ConditionalOnMissingBean(name = "omsKafkaTemplate")
    KafkaTemplate<String, String> omsKafkaTemplate(KafkaProperties kafkaProperties, OmsKafkaProperties props) {
        Map<String, Object> cfg = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        cfg.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, props.getMaxBlockMs());
        cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs());
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, props.getDeliveryTimeoutMs());
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
        template.setObservationEnabled(true);
        return template;
    }
}
