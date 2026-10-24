package com.enterprise.oms.common.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Adds {@code META-INF/oms-defaults.properties} as the LOWEST-precedence property source, so every service gets the
 * platform defaults (flyway locations incl. db/common, security headers for errors, actuator exposure, tracing off,
 * ...) and can override any of them in its own application.yml or environment.
 */
public class OmsDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "omsPlatformDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        try {
            Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/oms-defaults.properties"));
            environment.getPropertySources().addLast(new PropertiesPropertySource(SOURCE_NAME, props));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load oms-defaults.properties", e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
