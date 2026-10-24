package com.enterprise.oms.common.web;

import com.enterprise.oms.common.error.PayloadTooLargeException;
import com.enterprise.oms.common.error.ProblemWriter;
import com.enterprise.oms.common.events.EventJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebFiltersTest {

    private final ObjectMapper mapper = EventJson.configure(Jackson2ObjectMapperBuilder.json().build());

    @Test
    void correlationFilterEchoesValidIdAndBindsMdc() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
        req.addHeader(CorrelationIds.HEADER, "abc-123_X.y");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(MDC.get(CorrelationIds.MDC_KEY));

        new CorrelationIdFilter().doFilter(req, res, chain);

        assertThat(seen.get()).isEqualTo("abc-123_X.y");
        assertThat(res.getHeader(CorrelationIds.HEADER)).isEqualTo("abc-123_X.y");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void correlationFilterGeneratesIdWhenMissingOrUnsafe() throws Exception {
        for (String bad : new String[] {null, "has spaces", "x".repeat(100), "inject\nheader"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
            if (bad != null && !bad.contains("\n")) {
                req.addHeader(CorrelationIds.HEADER, bad);
            }
            MockHttpServletResponse res = new MockHttpServletResponse();
            new CorrelationIdFilter().doFilter(req, res, (rq, rs) -> { });
            assertThat(res.getHeader(CorrelationIds.HEADER)).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void oversizedContentLengthIsRejectedWith413ProblemJson() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(64 * 1024, new ProblemWriter(mapper));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.setContent(new byte[64 * 1024 + 1]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] chained = {false};

        MDC.put(CorrelationIds.MDC_KEY, "corr-413");
        try {
            filter.doFilter(req, res, (rq, rs) -> chained[0] = true);
        } finally {
            MDC.clear();
        }

        assertThat(chained[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = mapper.readTree(res.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.get("type").asText()).isEqualTo("https://oms.example/problems/payload-too-large");
        assertThat(body.get("status").asInt()).isEqualTo(413);
        assertThat(body.get("correlationId").asText()).isEqualTo("corr-413");
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/orders");
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.get("errors").isArray()).isTrue();
    }

    @Test
    void bodyWithinLimitPassesThrough() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(64 * 1024, new ProblemWriter(mapper));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x");
        req.setContent(new byte[64 * 1024]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] chained = {false};
        filter.doFilter(req, res, (rq, rs) -> chained[0] = true);
        assertThat(chained[0]).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void chunkedBodyIsCountedWhileRead() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(100, new ProblemWriter(mapper));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x") {
            @Override
            public long getContentLengthLong() {
                return -1; // unknown length (chunked)
            }
        };
        req.setContent(new byte[500]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            rq.getInputStream().readAllBytes();
        };
        assertThatThrownBy(() -> filter.doFilter(req, res, chain)).isInstanceOf(PayloadTooLargeException.class);
    }
}
