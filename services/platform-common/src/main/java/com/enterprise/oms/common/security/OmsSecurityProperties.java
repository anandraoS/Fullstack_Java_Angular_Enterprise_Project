package com.enterprise.oms.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code oms.security.*}. */
@ConfigurationProperties(prefix = "oms.security")
public class OmsSecurityProperties {

    /** Toggles the whole security auto-configuration (JWT decoder, filter chain, token beans). */
    private boolean enabled = true;
    private final Jwt jwt = new Jwt();

    public static class Jwt {
        /**
         * HS256 secret (>= 32 bytes UTF-8). If blank, env var OMS_JWT_SECRET is used. Never commit a value.
         */
        private String secret;
        /**
         * Generate a random in-memory secret when none is configured. ONLY for single-process tests / quick local
         * runs: every JVM gets a different secret, so tokens do not work across services. Never in prod.
         */
        private boolean allowEphemeralSecret = false;
        /** Expected and issued "iss" (HS256 mode). */
        private String issuer = "oms-dev-issuer";
        /** Expected and issued "aud". */
        private String audience = "oms-api";
        /** Name of the roles claim. */
        private String rolesClaim = "roles";
        /** OIDC mode: discover the JWKS and issuer from this issuer URI (RS256). Overrides the HS256 secret. */
        private String issuerUri;
        /** OIDC mode: explicit JWKS URI (RS256); issuer validated against issuer-uri if set. */
        private String jwkSetUri;
        /** Accepted clock skew when validating exp/nbf. */
        private Duration clockSkew = Duration.ofSeconds(30);
        /** Lifetime of minted SERVICE tokens. */
        private Duration serviceTokenTtl = Duration.ofMinutes(2);

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public boolean isAllowEphemeralSecret() { return allowEphemeralSecret; }
        public void setAllowEphemeralSecret(boolean allowEphemeralSecret) { this.allowEphemeralSecret = allowEphemeralSecret; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getRolesClaim() { return rolesClaim; }
        public void setRolesClaim(String rolesClaim) { this.rolesClaim = rolesClaim; }
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
        public Duration getClockSkew() { return clockSkew; }
        public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
        public Duration getServiceTokenTtl() { return serviceTokenTtl; }
        public void setServiceTokenTtl(Duration serviceTokenTtl) { this.serviceTokenTtl = serviceTokenTtl; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Jwt getJwt() { return jwt; }
}
