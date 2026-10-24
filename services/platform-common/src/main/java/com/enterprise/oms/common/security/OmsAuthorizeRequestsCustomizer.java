package com.enterprise.oms.common.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Extension point: declare a bean of this type to add URL rules to the default chain. Rules are applied AFTER the
 * built-in permit rules and the {@code /api/v1/internal/**} SERVICE rule, and BEFORE {@code anyRequest().authenticated()}.
 * <pre>{@code
 * @Bean OmsAuthorizeRequestsCustomizer rules() {
 *     return a -> a.requestMatchers(HttpMethod.POST, "/api/v1/customers").hasAnyRole("ADMIN", "OPERATOR");
 * }
 * }</pre>
 * Prefer {@code @PreAuthorize} on controller methods; use this only for coarse path rules.
 */
@FunctionalInterface
public interface OmsAuthorizeRequestsCustomizer {

    void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
