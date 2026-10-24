package com.enterprise.oms.gateway;

import com.enterprise.oms.gateway.auth.TokenController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fail-fast configuration rules (full application context start on an ephemeral port; settings passed as command-line args). */
class StartupConfigurationTest {

    /** Starts the app; {@code props} are given as {@code --key=value} arguments so they beat application.yml. */
    static ConfigurableApplicationContext run(String profile, String... props) {
        String[] args = new String[props.length + 1];
        args[0] = "--server.port=0";
        for (int i = 0; i < props.length; i++) {
            args[i + 1] = "--" + props[i];
        }
        return new SpringApplicationBuilder(GatewayApplication.class).web(WebApplicationType.REACTIVE).profiles(profile).run(args);
    }

    @Test
    void devProfileFailsToStartWithoutDemoPassword() {
        assertThatThrownBy(() -> run("dev", "oms.security.jwt.secret=" + TestEnv.SECRET, "oms.demo.password="))
                .hasStackTraceContaining("OMS_DEMO_PASSWORD");
    }

    @Test
    void devProfileFailsToStartWithTooShortDemoPassword() {
        assertThatThrownBy(() -> run("dev", "oms.security.jwt.secret=" + TestEnv.SECRET, "oms.demo.password=short"))
                .hasStackTraceContaining("OMS_DEMO_PASSWORD");
    }

    @Test
    void devProfileStartsWithADemoPassword() {
        try (ConfigurableApplicationContext ctx = run("dev", "oms.security.jwt.secret=" + TestEnv.SECRET,
                "oms.demo.password=" + TestEnv.DEMO_PASSWORD)) {
            assertThat(ctx.getBeanNamesForType(TokenController.class)).hasSize(1);
        }
    }

    @Test
    void startupFailsWithoutJwtSecret() {
        assertThatThrownBy(() -> run("prod", "oms.security.jwt.secret="))
                .hasStackTraceContaining("JWT secret missing");
    }

    @Test
    void startupFailsWithTooShortJwtSecret() {
        assertThatThrownBy(() -> run("prod", "oms.security.jwt.secret=too-short"))
                .hasStackTraceContaining("JWT secret too short");
    }

    @Test
    void nonDevProfileHasNoTokenEndpointBean() {
        try (ConfigurableApplicationContext ctx = run("prod", "oms.security.jwt.secret=" + TestEnv.SECRET)) {
            assertThat(ctx.getBeanNamesForType(TokenController.class)).isEmpty();
        }
    }

    @Test
    void invalidRouteTargetFailsStartup() {
        assertThatThrownBy(() -> run("prod", "oms.security.jwt.secret=" + TestEnv.SECRET,
                "oms.gateway.routes.order.uri=file:///etc/passwd"))
                .hasStackTraceContaining("Invalid gateway route target");
    }
}
