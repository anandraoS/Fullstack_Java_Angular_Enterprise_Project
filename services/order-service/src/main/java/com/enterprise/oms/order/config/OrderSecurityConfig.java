package com.enterprise.oms.order.config;

import com.enterprise.oms.common.security.OmsAuthorizeRequestsCustomizer;
import com.enterprise.oms.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * URL-level role rules of order-service (contract 5.1), evaluated BEFORE the request is bound and validated, so an
 * unauthorized caller gets 403 whatever the body or parameters look like (no validation-error oracle). The
 * {@code @PreAuthorize} annotations on the controllers stay as a second layer (defense in depth), and resource-level
 * checks (a customer only sees their own data, otherwise 404) are done in the services.
 */
@Configuration
public class OrderSecurityConfig {

    @Bean
    OmsAuthorizeRequestsCustomizer orderServiceAuthorizationRules() {
        return registry -> {
            registry.requestMatchers("/api/v1/admin/reconciliation/**").hasRole(Roles.ADMIN);
            registry.requestMatchers("/api/v1/admin/**").hasAnyRole(Roles.OPERATOR, Roles.ADMIN);
            registry.requestMatchers("/api/v1/ops/**").hasAnyRole(Roles.OPERATOR, Roles.ADMIN);
            registry.requestMatchers(HttpMethod.POST, "/api/v1/customers").hasAnyRole(Roles.OPERATOR, Roles.ADMIN);
            registry.requestMatchers(HttpMethod.GET, "/api/v1/customers").hasAnyRole(Roles.OPERATOR, Roles.ADMIN);
            registry.requestMatchers(HttpMethod.GET, "/api/v1/customers/*")
                    .hasAnyRole(Roles.CUSTOMER, Roles.OPERATOR, Roles.ADMIN);
            registry.requestMatchers("/api/v1/orders", "/api/v1/orders/**")
                    .hasAnyRole(Roles.CUSTOMER, Roles.OPERATOR, Roles.ADMIN);
        };
    }
}
