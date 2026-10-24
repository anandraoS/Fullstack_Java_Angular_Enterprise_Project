package com.enterprise.oms.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: failure-path counters must exist (at 0) from startup so dashboards/alerts have series to evaluate. */
class MessagingMetricsTest {

    @Test
    void preRegisterCreatesZeroedFailureCountersPerTopic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MessagingMetrics(registry).preRegister(List.of("oms.a.v1", " oms.b.v1 "));

        for (String topic : List.of("oms.a.v1", "oms.b.v1")) {
            assertThat(registry.find(MessagingMetrics.RETRIES).tag("topic", topic).counter()).isNotNull();
            assertThat(registry.find(MessagingMetrics.DEAD_LETTERED).tag("topic", topic).counter().count()).isZero();
            assertThat(registry.find(MessagingMetrics.DUPLICATES).tag("topic", topic).counter()).isNotNull();
        }
    }

    @Test
    void realIncrementsReuseThePreRegisteredSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagingMetrics m = new MessagingMetrics(registry);
        m.preRegister(List.of("oms.a.v1"));
        m.retry("oms.a.v1");
        m.deadLettered("oms.a.v1");
        assertThat(registry.find(MessagingMetrics.RETRIES).tag("topic", "oms.a.v1").counters()).hasSize(1);
        assertThat(registry.find(MessagingMetrics.RETRIES).tag("topic", "oms.a.v1").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(MessagingMetrics.DEAD_LETTERED).tag("topic", "oms.a.v1").counter().count()).isEqualTo(1.0);
    }

    @Test
    void preRegisterToleratesNull() {
        new MessagingMetrics(new SimpleMeterRegistry()).preRegister(null);
    }
}
