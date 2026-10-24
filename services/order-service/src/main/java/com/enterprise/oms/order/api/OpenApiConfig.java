package com.enterprise.oms.order.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Served at /v3/api-docs and /swagger-ui.html (public; contains no data). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI orderServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("OMS order-service API").version("1.0.0")
                        .description("Customers, orders and saga operations. Errors are RFC 7807 problem+json "
                                + "(codes: VALIDATION_FAILED, NOT_FOUND, FORBIDDEN, UNAUTHENTICATED, CONFLICT, "
                                + "INVALID_STATE_TRANSITION, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_REQUIRED, "
                                + "RATE_LIMITED, ...). All data is synthetic."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
