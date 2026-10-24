package com.enterprise.oms.common.error;

import com.enterprise.oms.common.testapp.TestApplication;
import com.enterprise.oms.common.security.JwtTokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class ExceptionHandlerApiTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenFactory tokens;

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + tokens.issue("u", List.of("ADMIN"), null, Duration.ofMinutes(5)));
    }

    private ResultActions problem(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.type").value("https://oms.example/problems/" + code.toLowerCase().replace('_', '-')))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void notFoundCarriesCorrelationIdAndInstance() throws Exception {
        MvcResult res = problem(mvc.perform(auth(get("/api/v1/test/not-found")).header("X-Correlation-Id", "corr-nf")),
                404, "NOT_FOUND")
                .andExpect(jsonPath("$.detail").value("Order 00000000-0000-0000-0000-000000000042 not found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/test/not-found"))
                .andExpect(jsonPath("$.correlationId").value("corr-nf"))
                .andReturn();
        assertThat(res.getResponse().getHeader("X-Correlation-Id")).isEqualTo("corr-nf");
    }

    @Test
    void domainExceptionsMapToContractStatusesAndCodes() throws Exception {
        problem(mvc.perform(auth(get("/api/v1/test/conflict"))), 409, "CONFLICT");
        problem(mvc.perform(auth(get("/api/v1/test/forbidden"))), 403, "FORBIDDEN");
        problem(mvc.perform(auth(get("/api/v1/test/state"))), 409, "INVALID_STATE_TRANSITION")
                .andExpect(jsonPath("$.detail").value("Order cannot transition from FULFILLED to CANCELLED"));
        problem(mvc.perform(auth(get("/api/v1/test/stock"))), 409, "INSUFFICIENT_STOCK")
                .andExpect(jsonPath("$.errors[0].message").value("requested 5, available 2"));
    }

    @Test
    void beanValidationProducesFieldErrors() throws Exception {
        problem(mvc.perform(auth(post("/api/v1/test/validate")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"qty\":0}")), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field=='qty')].message").isNotEmpty());
    }

    @Test
    void malformedJsonIs400WithoutParserInternals() throws Exception {
        problem(mvc.perform(auth(post("/api/v1/test/validate")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": ")), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.detail").value("Malformed or unreadable request body"));
    }

    @Test
    void unexpectedExceptionIs500WithoutLeakingDetails() throws Exception {
        MvcResult res = problem(mvc.perform(auth(get("/api/v1/test/boom"))), 500, "INTERNAL_ERROR").andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret_table").doesNotContain("select").doesNotContain("IllegalStateException")
                .doesNotContain("at com.enterprise");
    }

    @Test
    void frameworkErrorsAreEnriched() throws Exception {
        problem(mvc.perform(auth(post("/api/v1/test/ok"))), 405, "METHOD_NOT_ALLOWED");
        problem(mvc.perform(auth(post("/api/v1/test/validate")).contentType(MediaType.TEXT_PLAIN).content("x")),
                415, "UNSUPPORTED_MEDIA_TYPE");
        problem(mvc.perform(auth(get("/api/v1/test/does-not-exist"))), 404, "NOT_FOUND");
        problem(mvc.perform(auth(get("/api/v1/test/page")).param("size", "500")), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        problem(mvc.perform(auth(get("/api/v1/test/page")).param("sort", "password,asc")), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void pagingParamsAreAppliedWhenValid() throws Exception {
        mvc.perform(auth(get("/api/v1/test/page")).param("page", "3").param("size", "50").param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3)).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.sort").value("createdAt: DESC"));
    }

    @Test
    void idempotencyKeyHeaderRules() throws Exception {
        problem(mvc.perform(auth(get("/api/v1/test/idem"))), 400, "IDEMPOTENCY_KEY_REQUIRED");
        problem(mvc.perform(auth(get("/api/v1/test/idem")).header("Idempotency-Key", "no")), 400, "VALIDATION_FAILED");
        mvc.perform(auth(get("/api/v1/test/idem")).header("Idempotency-Key", "key-12345678"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.key").value("key-12345678"));
    }

    @Test
    void requestOver64KbIs413ThroughTheFullStack() throws Exception {
        String big = "{\"v\":\"" + "x".repeat(64 * 1024) + "\"}";
        problem(mvc.perform(auth(post("/api/v1/test/echo")).contentType(MediaType.APPLICATION_JSON).content(big)),
                413, "PAYLOAD_TOO_LARGE")
                .andExpect(header().exists("X-Correlation-Id"));
        mvc.perform(auth(post("/api/v1/test/echo")).contentType(MediaType.APPLICATION_JSON).content("{\"v\":\"small\"}"))
                .andExpect(status().isOk());
    }
}
