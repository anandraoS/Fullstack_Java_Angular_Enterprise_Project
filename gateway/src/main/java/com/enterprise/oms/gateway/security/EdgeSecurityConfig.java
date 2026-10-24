package com.enterprise.oms.gateway.security;

import com.enterprise.oms.common.security.JwtSecret;
import com.enterprise.oms.common.security.OmsSecurityProperties;
import com.enterprise.oms.common.security.Roles;
import com.enterprise.oms.gateway.config.GatewayProperties;
import com.enterprise.oms.gateway.problem.Problems;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Edge security: stateless JWT resource server (HS256 pinned, iss/aud/exp/sub validated), role-based authorization per
 * route as defense-in-depth (the backends re-check everything and stay authoritative: ownership checks, 404-for-other-
 * customer, etc.), problem+json 401/403, security headers, CORS. SERVICE-role tokens are never accepted at the edge.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(OmsSecurityProperties.class)
public class EdgeSecurityConfig {

    private static final String CUSTOMER = Roles.CUSTOMER;
    private static final String OPERATOR = Roles.OPERATOR;
    private static final String ADMIN = Roles.ADMIN;

    @Bean
    SecurityWebFilterChain edgeSecurityFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder,
                                                   OmsSecurityProperties props, Problems problems, Environment env,
                                                   CorsConfigurationSource cors) {
        ServerAuthenticationEntryPoint entryPoint = (exchange, ex) -> problems.write(exchange, HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "Unauthorized", "Authentication is required.",
                Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        ServerAccessDeniedHandler denied = (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Forbidden", "You are not allowed to perform this operation.");
        boolean dev = env.acceptsProfiles(Profiles.of("dev"));

        http.csrf(ServerHttpSecurity.CsrfSpec::disable) // stateless bearer-token API: no cookies/sessions to ride on
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(c -> c.requestCache(org.springframework.security.web.server.savedrequest.NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(c -> c.configurationSource(cors))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.mode(org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .hsts(Customizer.withDefaults())
                        .permissionsPolicy(p -> p.policy("geolocation=(), microphone=(), camera=()")))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied)
                        .jwt(j -> j.jwtDecoder(decoder)
                                .jwtAuthenticationConverter(jwt -> Mono.just(toAuthentication(jwt, props.getJwt().getRolesClaim())))));

        http.authorizeExchange(ex -> {
            ex.matchers(ServerWebExchangeMatchers.pathMatchers(HttpMethod.OPTIONS, "/**")).permitAll();
            ex.pathMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll();
            if (dev) {
                ex.pathMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll();
            }
            // never reachable from outside (also answered 404 earlier by the path guard)
            ex.pathMatchers("/api/v1/internal/**", "/fallback/**").denyAll();

            // ops
            ex.pathMatchers("/api/v1/ops/**").hasAnyRole(OPERATOR, ADMIN);

            // order-service
            ex.pathMatchers(HttpMethod.POST, "/api/v1/customers").hasAnyRole(ADMIN, OPERATOR);
            ex.pathMatchers(HttpMethod.GET, "/api/v1/customers").hasAnyRole(OPERATOR, ADMIN);
            ex.pathMatchers(HttpMethod.GET, "/api/v1/customers/*").hasAnyRole(CUSTOMER, OPERATOR, ADMIN);
            ex.pathMatchers("/api/v1/customers/**").denyAll();
            ex.pathMatchers("/api/v1/orders", "/api/v1/orders/**").hasAnyRole(CUSTOMER, OPERATOR, ADMIN);
            ex.pathMatchers("/api/v1/admin/reconciliation/**").hasRole(ADMIN);
            ex.pathMatchers("/api/v1/admin/orders/**", "/api/v1/admin/saga/**").hasAnyRole(OPERATOR, ADMIN);

            // inventory-service
            ex.pathMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").hasAnyRole(CUSTOMER, OPERATOR, ADMIN);
            ex.pathMatchers("/api/v1/products", "/api/v1/products/**").hasRole(ADMIN);
            ex.pathMatchers("/api/v1/inventory/**").hasAnyRole(OPERATOR, ADMIN);

            // fulfillment-service (customers not exposed)
            ex.pathMatchers("/api/v1/fulfillments/**", "/api/v1/admin/fulfillments/**").hasAnyRole(OPERATOR, ADMIN);

            // notification-service
            ex.pathMatchers("/api/v1/admin/notifications/**").hasAnyRole(OPERATOR, ADMIN);
            ex.pathMatchers("/api/v1/notifications/**").hasAnyRole(CUSTOMER, OPERATOR, ADMIN);

            // anything else: unauthenticated -> 401, authenticated -> routed nowhere -> 404
            ex.anyExchange().authenticated();
        });
        return http.build();
    }

    static AbstractAuthenticationToken toAuthentication(Jwt jwt, String rolesClaim) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object claim = jwt.getClaims().get(rolesClaim);
        if (claim instanceof Collection<?> roles) {
            for (Object r : roles) {
                if (r instanceof String s && Roles.ALL.contains(s)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + s));
                }
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(OmsSecurityProperties props, Environment env) {
        OmsSecurityProperties.Jwt jwt = props.getJwt();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(jwt.getClockSkew()),
                new JwtIssuerValidator(jwt.getIssuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(jwt.getAudience())),
                requiredClaims());
        boolean oidc = notBlank(jwt.getIssuerUri()) || notBlank(jwt.getJwkSetUri());
        NimbusReactiveJwtDecoder decoder;
        if (oidc) { // RS256 via JWKS (not exercised by the tests; see README)
            decoder = notBlank(jwt.getJwkSetUri())
                    ? NimbusReactiveJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build()
                    : (NimbusReactiveJwtDecoder) ReactiveJwtDecoders.fromIssuerLocation(jwt.getIssuerUri());
        } else {
            JwtSecret secret = JwtSecret.resolve(jwt, env); // fails startup when missing / too short
            decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(secret.bytes(), "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
        }
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** exp and sub are mandatory (the timestamp validator alone accepts tokens without exp). */
    private static OAuth2TokenValidator<Jwt> requiredClaims() {
        return token -> token.getExpiresAt() != null && token.getSubject() != null && !token.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** CORS: only exact configured origins; none configured (default) = no cross-origin access. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.getCors().getAllowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("oms.gateway.cors.allowed-origins must list exact origins; wildcards are not allowed");
        }
        if (!origins.isEmpty()) {
            CorsConfiguration c = new CorsConfiguration();
            c.setAllowedOrigins(origins);
            c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id", "Accept"));
            c.setExposedHeaders(List.of("Idempotent-Replay", "Location", "X-Correlation-Id", "Retry-After",
                    "X-RateLimit-Remaining"));
            c.setAllowCredentials(false);
            c.setMaxAge(props.getCors().getMaxAge() == null ? Duration.ofHours(1) : props.getCors().getMaxAge());
            source.registerCorsConfiguration("/api/**", c);
        }
        return source;
    }
}
