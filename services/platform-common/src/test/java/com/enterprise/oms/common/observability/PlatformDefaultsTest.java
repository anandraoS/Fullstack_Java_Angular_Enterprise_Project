package com.enterprise.oms.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.oms.common.autoconfigure.OmsDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/** Regression: every service must publish latency histogram buckets (dashboards/alerts use histogram_quantile). */
class PlatformDefaultsTest {

    @Test
    void latencyHistogramsAreOnByDefault() {
        StandardEnvironment env = new StandardEnvironment();
        new OmsDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests")).isEqualTo("true");
        assertThat(env.getProperty("management.metrics.distribution.percentiles-histogram.spring.kafka.listener")).isEqualTo("true");
    }
}
