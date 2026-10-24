package com.enterprise.oms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Security regression (integration finding: threat-model T-docs): the machine-readable API description and Swagger UI
 * must NOT be served in profile {@code prod}. Loads the real application.yml / application-prod.yml through Spring Boot's
 * config-data loader (no database or Kafka needed, so it runs everywhere, unlike the Postgres integration tests).
 */
class ProdProfileDocsDisabledTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void prodProfileDisablesApiDocsAndSwaggerUi() {
        runner.withSystemProperties("spring.profiles.active=prod").run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(ctx.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        });
    }

    @Test
    void controlDevProfileLeavesDocsEnabled() {
        // proves the assertion above is meaningful: outside prod the flags are not switched off
        runner.withSystemProperties("spring.profiles.active=dev").run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("springdoc.api-docs.enabled", "true")).isEqualTo("true");
            assertThat(ctx.getEnvironment().getProperty("springdoc.swagger-ui.enabled", "true")).isEqualTo("true");
        });
    }
}
