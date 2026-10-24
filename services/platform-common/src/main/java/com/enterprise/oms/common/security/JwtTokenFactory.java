package com.enterprise.oms.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints HS256 JWTs with the OMS claim set: sub, roles, customerId, iss, aud, iat, exp, jti. Used by the gateway
 * dev-token endpoint, {@link ServiceTokenProvider} and tests (a negative ttl yields an already expired token).
 */
public class JwtTokenFactory {

    private final byte[] secret;
    private final String issuer;
    private final String audience;

    public JwtTokenFactory(JwtSecret secret, String issuer, String audience) {
        this.secret = secret.bytes();
        this.issuer = issuer;
        this.audience = audience;
    }

    /** Same issuer/audience as {@code this}, different secret/audience - handy to build "wrong aud" test tokens. */
    public JwtTokenFactory withAudience(String otherAudience) {
        return new JwtTokenFactory(JwtSecret.of(new String(secret, java.nio.charset.StandardCharsets.UTF_8)), issuer,
                otherAudience);
    }

    /** Same secret/audience, different issuer - for negative tests ("wrong iss"). */
    public JwtTokenFactory withIssuer(String otherIssuer) {
        return new JwtTokenFactory(JwtSecret.of(new String(secret, java.nio.charset.StandardCharsets.UTF_8)),
                otherIssuer, audience);
    }

    public String issue(String subject, Collection<String> roles, UUID customerId, Duration ttl) {
        Instant now = Instant.now();
        return issue(subject, roles, customerId, now, now.plus(ttl));
    }

    public String issue(String subject, Collection<String> roles, UUID customerId, Instant issuedAt,
                        Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.copyOf(roles));
        if (customerId != null) {
            claims.claim("customerId", customerId.toString());
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign JWT", e);
        }
    }
}
