package com.enterprise.oms.common.security;

import com.enterprise.oms.common.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class SecurityApiTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenFactory tokens;
    @Autowired ServiceTokenProvider serviceTokens;

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String token(String... roles) {
        return tokens.issue("user-1", List.of(roles), null, Duration.ofMinutes(5));
    }

    private ResultActions getWith(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private void assertProblem(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.type").value("https://oms.example/problems/" + code.toLowerCase().replace('_', '-')))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        getWith("/api/v1/test/ok", token("CUSTOMER")).andExpect(status().isOk());
    }

    @Test
    void missingTokenIs401Problem() throws Exception {
        ResultActions r = mvc.perform(get("/api/v1/test/ok"));
        assertProblem(r, 401, "UNAUTHENTICATED");
        r.andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void garbageTokenIs401() throws Exception {
        assertProblem(getWith("/api/v1/test/ok", "not.a.jwt"), 401, "UNAUTHENTICATED");
    }

    @Test
    void expiredTokenIs401() throws Exception {
        String expired = tokens.issue("user-1", List.of("CUSTOMER"), null, Duration.ofMinutes(-10));
        assertProblem(getWith("/api/v1/test/ok", expired), 401, "UNAUTHENTICATED");
    }

    @Test
    void wrongAudienceIs401() throws Exception {
        String t = tokens.withAudience("other-api").issue("user-1", List.of("CUSTOMER"), null, Duration.ofMinutes(5));
        assertProblem(getWith("/api/v1/test/ok", t), 401, "UNAUTHENTICATED");
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        String t = tokens.withIssuer("evil-issuer").issue("user-1", List.of("CUSTOMER"), null, Duration.ofMinutes(5));
        assertProblem(getWith("/api/v1/test/ok", t), 401, "UNAUTHENTICATED");
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() throws Exception {
        JwtTokenFactory other = new JwtTokenFactory(JwtSecret.random(), "oms-dev-issuer", "oms-api");
        String t = other.issue("user-1", List.of("ADMIN"), null, Duration.ofMinutes(5));
        assertProblem(getWith("/api/v1/test/admin", t), 401, "UNAUTHENTICATED");
    }

    @Test
    void tamperedPayloadIs401() throws Exception {
        String valid = token("CUSTOMER");
        String[] parts = valid.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ADMIN");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertProblem(getWith("/api/v1/test/admin", forged), 401, "UNAUTHENTICATED");
    }

    @Test
    void algNoneTokenIs401() throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"x\",\"roles\":[\"ADMIN\"],\"iss\":\"oms-dev-issuer\",\"aud\":\"oms-api\",\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 600) + "}").getBytes(StandardCharsets.UTF_8));
        assertProblem(getWith("/api/v1/test/admin", header + "." + body + "."), 401, "UNAUTHENTICATED");
    }

    @Test
    void methodSecurityGives403Problem() throws Exception {
        assertProblem(getWith("/api/v1/test/admin", token("CUSTOMER")), 403, "FORBIDDEN");
        getWith("/api/v1/test/admin", token("ADMIN")).andExpect(status().isOk());
    }

    @Test
    void unknownRolesAreIgnored() throws Exception {
        assertProblem(getWith("/api/v1/test/admin", token("SUPERUSER", "admin-ish")), 403, "FORBIDDEN");
    }

    @Test
    void internalEndpointsRequireServiceRole() throws Exception {
        assertProblem(getWith("/api/v1/internal/ping", token("ADMIN")), 403, "FORBIDDEN");
        assertProblem(mvc.perform(get("/api/v1/internal/ping")), 401, "UNAUTHENTICATED");
        getWith("/api/v1/internal/ping", serviceTokens.token()).andExpect(status().isOk());
    }

    @Test
    void currentUserExposesIdRolesAndCustomerId() throws Exception {
        UUID customerId = UUID.randomUUID();
        String t = tokens.issue("cust-sub", List.of("CUSTOMER"), customerId, Duration.ofMinutes(5));
        getWith("/api/v1/test/me", t).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cust-sub"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()));
    }

    @Test
    void securityHeadersAndCorrelationEcho() throws Exception {
        getWith("/api/v1/test/ok", token("CUSTOMER"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Correlation-Id"));
        mvc.perform(get("/api/v1/test/ok").header("X-Correlation-Id", "my-corr-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("CUSTOMER"))))
                .andExpect(header().string("X-Correlation-Id", "my-corr-1"));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        // api-docs path is permitted by the chain (either 200 or 404 if springdoc is off, never 401/403)
        int s = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(s).isNotIn(401, 403);
    }

    @Test
    void opsMessagingEndpointDegradesGracefullyWithoutKafka() throws Exception {
        mvc.perform(get("/api/v1/ops/messaging").header(HttpHeaders.AUTHORIZATION, bearer(token("CUSTOMER"))))
                .andExpect(status().isForbidden());
        getWith("/api/v1/ops/messaging", token("OPERATOR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("test-service"))
                .andExpect(jsonPath("$.consumerGroup").value("test-service"))
                .andExpect(jsonPath("$.lagAvailable").value(false))
                .andExpect(jsonPath("$.topics[0].topic").value("oms.inventory.events.v1"))
                .andExpect(jsonPath("$.topics[0].consumerLag").doesNotExist())
                .andExpect(jsonPath("$.dltTopics[0]").value("oms.inventory.events.v1.DLT"))
                .andExpect(jsonPath("$.outboxPending").value(0));
    }
}
