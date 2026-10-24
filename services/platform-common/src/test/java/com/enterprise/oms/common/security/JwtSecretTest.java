package com.enterprise.oms.common.security;

import com.enterprise.oms.common.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretTest {

    @Test
    void failsFastWhenAbsent() {
        OmsSecurityProperties.Jwt props = new OmsSecurityProperties.Jwt();
        assertThatThrownBy(() -> JwtSecret.resolve(props, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OMS_JWT_SECRET");
    }

    @Test
    void failsFastWhenShorterThan32Bytes() {
        OmsSecurityProperties.Jwt props = new OmsSecurityProperties.Jwt();
        props.setSecret("x".repeat(31));
        assertThatThrownBy(() -> JwtSecret.resolve(props, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too short");
        MockEnvironment env = new MockEnvironment().withProperty("OMS_JWT_SECRET", "short");
        assertThatThrownBy(() -> JwtSecret.resolve(new OmsSecurityProperties.Jwt(), env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsEnvVarAndAcceptsExactly32Bytes() {
        String generated = JwtSecret.randomSecretString().substring(0, 32);
        MockEnvironment env = new MockEnvironment().withProperty("OMS_JWT_SECRET", generated);
        JwtSecret secret = JwtSecret.resolve(new OmsSecurityProperties.Jwt(), env);
        assertThat(secret.bytes()).hasSize(32);
        assertThat(secret.ephemeral()).isFalse();
        assertThat(secret.toString()).doesNotContain(generated);
    }

    @Test
    void ephemeralSecretOnlyWhenExplicitlyAllowed() {
        OmsSecurityProperties.Jwt props = new OmsSecurityProperties.Jwt();
        props.setAllowEphemeralSecret(true);
        JwtSecret a = JwtSecret.resolve(props, new MockEnvironment());
        JwtSecret b = JwtSecret.resolve(props, new MockEnvironment());
        assertThat(a.ephemeral()).isTrue();
        assertThat(a.bytes().length).isGreaterThanOrEqualTo(32);
        assertThat(a.bytes()).isNotEqualTo(b.bytes());
    }

    private void startWith(String... args) {
        // command-line args have the highest precedence (they override src/test/resources/application.yml)
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.SERVLET).run(args)) {
            ctx.getId();
        }
    }

    @Test
    void applicationStartupFailsFastWithShortSecret() {
        assertThatThrownBy(() -> startWith("--server.port=0", "--oms.security.jwt.allow-ephemeral-secret=false",
                "--oms.security.jwt.secret=" + "z".repeat(10), "--spring.datasource.url=jdbc:h2:mem:failfast1;MODE=PostgreSQL"))
                .hasStackTraceContaining("JWT secret too short");
    }

    @Test
    void applicationStartupFailsFastWithoutSecret() {
        assertThatThrownBy(() -> startWith("--server.port=0", "--oms.security.jwt.allow-ephemeral-secret=false",
                "--oms.security.jwt.secret=", "--spring.datasource.url=jdbc:h2:mem:failfast2;MODE=PostgreSQL"))
                .hasStackTraceContaining("JWT secret missing");
    }
}
