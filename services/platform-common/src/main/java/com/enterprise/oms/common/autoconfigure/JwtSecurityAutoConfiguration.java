package com.enterprise.oms.common.autoconfigure;

import com.enterprise.oms.common.error.ProblemWriter;
import com.enterprise.oms.common.security.JwtSecret;
import com.enterprise.oms.common.security.JwtTokenFactory;
import com.enterprise.oms.common.security.OmsAuthorizeRequestsCustomizer;
import com.enterprise.oms.common.security.OmsSecurityProperties;
import com.enterprise.oms.common.security.ProblemSecurityHandlers;
import com.enterprise.oms.common.security.Roles;
import com.enterprise.oms.common.security.ServiceTokenProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.core.convert.converter.Converter;

import javax.crypto.spec.SecretKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Stateless JWT resource server for servlet services.
 * <ul>
 *   <li>HS256 mode (default): secret from {@code oms.security.jwt.secret} / env {@code OMS_JWT_SECRET} (>= 32 bytes,
 *       fail fast if absent unless {@code allow-ephemeral-secret=true}). Validates signature, exp/nbf, iss, aud.</li>
 *   <li>OIDC mode: set {@code oms.security.jwt.issuer-uri} and/or {@code jwk-set-uri} (RS256 via JWKS). In this mode
 *       no HS256 secret/service-token beans are created.</li>
 *   <li>{@code roles} claim -> {@code ROLE_*} authorities (only CUSTOMER, OPERATOR, ADMIN, SERVICE are honoured).</li>
 *   <li>CSRF is disabled on purpose: the API is stateless, never uses cookies/sessions and authenticates solely via
 *       the {@code Authorization: Bearer} header, which browsers do not attach automatically, so there is no ambient
 *       credential for a cross-site request to ride on.</li>
 * </ul>
 * Back off by defining your own {@link SecurityFilterChain}. Toggle: {@code oms.security.enabled}.
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class}, after = OmsCoreAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class})
@ConditionalOnProperty(prefix = "oms.security", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(OmsSecurityProperties.class)
@EnableMethodSecurity
public class JwtSecurityAutoConfiguration {

    /** True when neither issuer-uri nor jwk-set-uri is configured (=> HS256 mode). */
    static class Hs256ModeCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            boolean oidc = hasText(env.getProperty("oms.security.jwt.issuer-uri"))
                    || hasText(env.getProperty("oms.security.jwt.jwk-set-uri"));
            return oidc ? ConditionOutcome.noMatch("OIDC mode configured") : ConditionOutcome.match("HS256 mode");
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }

    @Bean
    @Conditional(Hs256ModeCondition.class)
    @ConditionalOnMissingBean
    JwtSecret omsJwtSecret(OmsSecurityProperties props, Environment env) {
        return JwtSecret.resolve(props.getJwt(), env);
    }

    @Bean
    @Conditional(Hs256ModeCondition.class)
    @ConditionalOnMissingBean
    JwtTokenFactory jwtTokenFactory(JwtSecret secret, OmsSecurityProperties props) {
        return new JwtTokenFactory(secret, props.getJwt().getIssuer(), props.getJwt().getAudience());
    }

    @Bean
    @Conditional(Hs256ModeCondition.class)
    @ConditionalOnMissingBean
    ServiceTokenProvider serviceTokenProvider(JwtTokenFactory factory, OmsSecurityProperties props,
                                              @Value("${spring.application.name:oms-service}") String appName) {
        return new ServiceTokenProvider(factory, appName, props.getJwt().getServiceTokenTtl());
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder omsJwtDecoder(OmsSecurityProperties props, ObjectProvider<JwtSecret> secret) {
        OmsSecurityProperties.Jwt jwt = props.getJwt();
        boolean hasJwk = jwt.getJwkSetUri() != null && !jwt.getJwkSetUri().isBlank();
        boolean hasIssuer = jwt.getIssuerUri() != null && !jwt.getIssuerUri().isBlank();
        NimbusJwtDecoder decoder;
        OAuth2TokenValidator<Jwt> issuerValidator;
        if (hasJwk || hasIssuer) {
            decoder = hasJwk ? NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build()
                    : (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(jwt.getIssuerUri());
            issuerValidator = hasIssuer ? new JwtIssuerValidator(jwt.getIssuerUri()) : t -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
        } else {
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getObject().bytes(), "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
            issuerValidator = new JwtIssuerValidator(jwt.getIssuer());
        }
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(jwt.getAudience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(jwt.getClockSkew()),
                issuerValidator, audience));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean(name = "omsJwtAuthenticationConverter")
    Converter<Jwt, AbstractAuthenticationToken> omsJwtAuthenticationConverter(OmsSecurityProperties props) {
        String claim = props.getJwt().getRolesClaim();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(token -> authorities(token, claim));
        return converter;
    }

    static Collection<GrantedAuthority> authorities(Jwt token, String claim) {
        Object raw = token.getClaims().get(claim);
        List<String> roles = raw instanceof Collection<?> c ? c.stream().map(String::valueOf).toList()
                : raw instanceof String s ? List.of(s.split("[ ,]+")) : List.of();
        return roles.stream().map(r -> r.trim().toUpperCase(Locale.ROOT)).filter(Roles.ALL::contains)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toList());
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain omsSecurityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                               Converter<Jwt, AbstractAuthenticationToken> converter,
                                               ProblemWriter writer,
                                               ObjectProvider<OmsAuthorizeRequestsCustomizer> customizers)
            throws Exception {
        ProblemSecurityHandlers.EntryPoint entryPoint = new ProblemSecurityHandlers.EntryPoint(writer);
        ProblemSecurityHandlers.Denied denied = new ProblemSecurityHandlers.Denied(writer);
        http
                // CSRF off: stateless bearer-token API, no cookies/sessions (see class javadoc).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(Customizer.withDefaults()))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                            "/actuator/prometheus").permitAll();
                    a.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                            .permitAll();
                    a.requestMatchers("/api/v1/internal/**").hasRole(Roles.SERVICE);
                    customizers.orderedStream().forEach(c -> c.customize(a));
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied));
        return http.build();
    }
}
