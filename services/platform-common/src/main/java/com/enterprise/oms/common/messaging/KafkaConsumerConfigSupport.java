package com.enterprise.oms.common.messaging;

import com.enterprise.oms.common.web.CorrelationIds;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the standard consumer infrastructure: String key/value deserialization (the envelope is parsed by
 * {@link EventRouter}/{@code EventJson}, so malformed JSON becomes a non-retryable {@link MalformedEventException}
 * and not a poison pill that blocks the partition), {@code AckMode.RECORD}, {@link DefaultErrorHandler} with
 * exponential backoff, {@link DeadLetterPublishingRecoverer} to {@code <topic>.DLT}, and correlation-id MDC.
 */
public class KafkaConsumerConfigSupport {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfigSupport.class);

    /** Exception types that skip retries and go straight to the DLT. */
    public static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
            NonRetryableEventException.class,
            IllegalArgumentException.class,
            jakarta.validation.ConstraintViolationException.class,
            DeserializationException.class,
            MessageConversionException.class,
            ClassCastException.class);

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, String> dltTemplate;
    private final MessagingMetrics metrics;
    private final MessagingProperties props;
    private MeterRegistry meterRegistry;

    public KafkaConsumerConfigSupport(KafkaProperties kafkaProperties, KafkaTemplate<String, String> dltTemplate,
                                      MessagingMetrics metrics, MessagingProperties props) {
        this.kafkaProperties = kafkaProperties;
        this.dltTemplate = dltTemplate;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * Binds the Kafka client metrics of every consumer created by {@link #containerFactory(String)} to Micrometer
     * ({@code kafka_consumer_*}, e.g. {@code kafka_consumer_fetch_manager_records_lag_max}, used by the consumer-lag alert and
     * dashboard). Spring Boot only does this for ITS consumer factory, not for the custom one built here.
     */
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** True when the throwable (or any cause) is classified as non-retryable. */
    public static boolean isNonRetryable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            for (Class<? extends Exception> type : NON_RETRYABLE) {
                if (type.isInstance(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Container factory for the given consumer group; reference it as {@code containerFactory} on listeners. */
    public ConcurrentKafkaListenerContainerFactory<String, String> containerFactory(String groupId) {
        Map<String, Object> cfg = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(cfg);
        if (meterRegistry != null) {
            consumerFactory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        }
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(props.getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setRecordInterceptor(new CorrelationRecordInterceptor());
        factory.setCommonErrorHandler(errorHandler());
        return factory;
    }

    /** Error handler: backoff 500ms x2 (max 5s), 4 attempts by default, then DLT; classification as above. */
    public DefaultErrorHandler errorHandler() {
        MessagingProperties.Retry r = props.getRetry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, r.getMaxAttempts() - 1));
        backOff.setInitialInterval(r.getInitialIntervalMs());
        backOff.setMultiplier(r.getMultiplier());
        backOff.setMaxInterval(r.getMaxIntervalMs());

        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(dltTemplate,
                (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));
        dlt.setHeadersFunction(KafkaConsumerConfigSupport::dltHeaders);

        ConsumerRecordRecoverer recoverer = (rec, ex) -> {
            dlt.accept(rec, ex);
            metrics.deadLettered(rec.topic());
            log.error("Dead-lettered record topic={} partition={} offset={} to {}.DLT cause={}", rec.topic(),
                    rec.partition(), rec.offset(), rec.topic(), rootMessage(ex));
        };

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NON_RETRYABLE.toArray(new Class[0]));
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                if (!isNonRetryable(ex) && deliveryAttempt < r.getMaxAttempts()) {
                    metrics.retry(record.topic());
                }
                log.warn("Delivery attempt {} failed topic={} offset={}: {}", deliveryAttempt, record.topic(),
                        record.offset(), rootMessage(ex));
            }
        });
        return handler;
    }

    private static Headers dltHeaders(ConsumerRecord<?, ?> rec, Exception ex) {
        RecordHeaders h = new RecordHeaders();
        Throwable root = unwrapListener(ex);
        h.add("x-exception-class", root.getClass().getName().getBytes(StandardCharsets.UTF_8));
        h.add("x-exception-message", String.valueOf(root.getMessage()).getBytes(StandardCharsets.UTF_8));
        h.add("x-original-topic", rec.topic().getBytes(StandardCharsets.UTF_8));
        h.add("x-original-partition", String.valueOf(rec.partition()).getBytes(StandardCharsets.UTF_8));
        h.add("x-original-offset", String.valueOf(rec.offset()).getBytes(StandardCharsets.UTF_8));
        return h;
    }

    /** The exception thrown by the handler (strips the container's ListenerExecutionFailedException wrapper). */
    private static Throwable unwrapListener(Throwable t) {
        Throwable c = t;
        while (c instanceof org.springframework.kafka.listener.ListenerExecutionFailedException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = rootCause(t);
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    /** Puts the {@code correlationId} Kafka header into the MDC for the duration of the listener call. */
    static final class CorrelationRecordInterceptor implements RecordInterceptor<String, String> {
        @Override
        public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                        Consumer<String, String> consumer) {
            Header h = record.headers().lastHeader(CorrelationIds.KAFKA_HEADER);
            String id = h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
            MDC.put(CorrelationIds.MDC_KEY, CorrelationIds.isValid(id) ? id : CorrelationIds.newId());
            return record;
        }

        @Override
        public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
