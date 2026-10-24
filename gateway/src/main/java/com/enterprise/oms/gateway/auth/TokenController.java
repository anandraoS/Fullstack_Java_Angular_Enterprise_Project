package com.enterprise.oms.gateway.auth;

import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.common.security.JwtSecret;
import com.enterprise.oms.common.security.JwtTokenFactory;
import com.enterprise.oms.common.security.OmsSecurityProperties;
import com.enterprise.oms.gateway.problem.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DEV-ONLY token endpoint {@code POST /api/v1/auth/token} (profile {@code dev}; the bean does not exist otherwise).
 * Issues HS256 JWTs for the four demo users. Never logs credentials; failures are generic and identical for wrong user and
 * wrong password. Not a production identity provider: production uses an OIDC issuer (see README).
 */
@RestController
@Profile("dev")
public class TokenController {

    static final long EXPIRES_IN_SECONDS = 3600;
    private static final int MAX_FIELD = 128;
    private static final Logger log = LoggerFactory.getLogger(TokenController.class);

    public record TokenRequest(String username, String password) {
    }

    private final DemoCredentials credentials;
    private final LoginThrottle throttle;
    private final JwtTokenFactory tokens;
    private final Problems problems;

    public TokenController(DemoCredentials credentials, LoginThrottle throttle, OmsSecurityProperties props,
                           Environment env, Problems problems) {
        this.credentials = credentials;
        this.throttle = throttle;
        this.problems = problems;
        OmsSecurityProperties.Jwt jwt = props.getJwt();
        this.tokens = new JwtTokenFactory(JwtSecret.resolve(jwt, env), jwt.getIssuer(), jwt.getAudience());
    }

    @PostMapping(path = "/api/v1/auth/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> token(@RequestBody Mono<TokenRequest> body, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return body.map(Optional::of).defaultIfEmpty(Optional.empty()).map(opt -> {
            TokenRequest req = opt.orElse(null);
            if (req == null || !valid(req.username()) || !valid(req.password())) {
                return problem(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Bad Request",
                        "username and password are required.", Map.of());
            }
            Duration locked = throttle.lockedFor(ip, req.username());
            if (!locked.isZero()) {
                long retry = Math.max(1, locked.toSeconds() + (locked.toMillisPart() > 0 ? 1 : 0));
                log.warn("token request throttled ip={} user={}", ip, safe(req.username()));
                return problem(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too Many Requests",
                        "Too many failed attempts. Retry later.", Map.of(HttpHeaders.RETRY_AFTER, Long.toString(retry)));
            }
            Optional<DemoCredentials.DemoUser> user = credentials.authenticate(req.username(), req.password());
            if (user.isEmpty()) {
                throttle.recordFailure(ip, req.username());
                log.warn("token request rejected ip={} user={}", ip, safe(req.username()));
                return problem(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unauthorized",
                        "Invalid credentials.", Map.of());
            }
            throttle.recordSuccess(ip, req.username());
            DemoCredentials.DemoUser u = user.get();
            String jwt = tokens.issue(u.username(), u.roles(), u.customerId(), Duration.ofSeconds(EXPIRES_IN_SECONDS));
            log.info("token issued user={} roles={}", u.username(), u.roles());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accessToken", jwt);
            out.put("tokenType", "Bearer");
            out.put("expiresIn", EXPIRES_IN_SECONDS);
            out.put("username", u.username());
            out.put("roles", List.copyOf(u.roles()));
            out.put("customerId", u.customerId() == null ? null : u.customerId().toString());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body((Object) out);
        });
    }

    private ResponseEntity<Object> problem(ServerWebExchange exchange, HttpStatus status, String code, String title,
                                           String detail, Map<String, String> headers) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(Problems.PROBLEM_JSON);
        headers.forEach(b::header);
        if (status == HttpStatus.UNAUTHORIZED) {
            b.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return b.body(problems.body(exchange, status, code, title, detail));
    }

    private static boolean valid(String s) {
        return s != null && !s.isBlank() && s.length() <= MAX_FIELD;
    }

    private static String safe(String username) {
        String s = LogMasking.stripNewlines(username == null ? "" : username);
        return s.length() > 32 ? s.substring(0, 32) + "..." : s;
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
