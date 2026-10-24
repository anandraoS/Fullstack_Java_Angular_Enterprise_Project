package com.enterprise.oms.notification.config;

import com.enterprise.oms.notification.sender.NotificationSender;
import com.enterprise.oms.notification.sender.SimulatedSender;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class NotificationConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The only sender shipped is the simulation; declare your own {@link NotificationSender} bean to replace it. */
    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender simulatedSender(NotificationProperties props) {
        return new SimulatedSender(props);
    }

    @Bean
    OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info().title("OMS notification-service API").version("v1")
                        .description("Simulated customer notifications (no real provider is contacted)"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
