package com.enterprise.oms.order.api;

import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Metrics, OpenAPI, health probes, platform filters and the documented configuration. */
class OperationalEndpointsTest extends AbstractOrderSpringTest {

    @Autowired Environment env;
    @Autowired DataSource dataSource;

    @Test
    void prometheusExposesTheContractMetricsIncludingHistogramBuckets() throws Exception {
        Customer c = newCustomer();
        mvc.perform(get("/api/v1/orders").header("Authorization", JwtTestTokens.customer(c.getId()))).andExpect(status().isOk());
        validatedOrder();

        String scrape = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains("oms_saga_active")
                .contains("oms_order_processing_duration_seconds_bucket{")
                .contains("outcome=\"FULFILLED\"")
                .contains("oms_outbox_pending")
                .contains("http_server_requests_seconds_bucket{")        // publish-percentiles-histogram for http.server.requests
                .contains("service=\"order-service\"");
    }

    @Test
    void theProcessingTimerHasAPercentileHistogramAndTheSagaGaugeCountsActiveSagas() throws Exception {
        UUID id = confirmedOrder();
        deliverFulfillment(com.enterprise.oms.order.support.TestEvents.fulfillmentCompleted(id, "T"));
        Timer timer = meters.get("oms.order.processing.duration").tag("outcome", "FULFILLED").timer();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
        assertThat(timer.takeSnapshot().histogramCounts()).isNotEmpty();

        UUID active = validatedOrder();
        double gauge = meters.get("oms.saga.active").gauge().value();
        assertThat(active).isNotNull();
        assertThat(gauge).isGreaterThanOrEqualTo(0);
    }

    @Test
    void openApiDescribesTheContractEndpointsAndTheBearerScheme() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = mapper.readTree(doc);
        JsonNode paths = root.get("paths");
        for (String p : List.of("/api/v1/orders", "/api/v1/orders/{id}", "/api/v1/orders/{id}/history",
                "/api/v1/orders/{id}/cancel", "/api/v1/customers", "/api/v1/customers/{id}",
                "/api/v1/admin/orders/failed", "/api/v1/admin/orders/{id}/retry", "/api/v1/admin/saga/{orderId}",
                "/api/v1/admin/reconciliation/run")) {
            assertThat(paths.has(p)).as(p).isTrue();
        }
        assertThat(paths.get("/api/v1/orders").get("post").get("responses").has("202")).isTrue();
        assertThat(paths.get("/api/v1/orders").get("post").get("summary").asText()).contains("idempotent");
        List<String> postParams = new ArrayList<>();
        paths.get("/api/v1/orders").get("post").get("parameters").forEach(n -> postParams.add(n.get("name").asText()));
        assertThat(postParams).contains("Idempotency-Key");
        assertThat(root.get("components").get("securitySchemes").get("bearerAuth").get("scheme").asText()).isEqualTo("bearer");
        assertThat(root.get("info").get("title").asText()).contains("order-service");
    }

    @Test
    void livenessAndReadinessProbesAreUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("UP")));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void correlationIdIsEchoedGeneratedOrReplacedWhenInvalid() throws Exception {
        String token = JwtTestTokens.admin();
        mvc.perform(get("/api/v1/orders").header("Authorization", token).header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"));
        String generated = mvc.perform(get("/api/v1/orders").header("Authorization", token))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(generated).hasSize(36);
        String replaced = mvc.perform(get("/api/v1/orders").header("Authorization", token).header("X-Correlation-Id", "bad id with spaces\r\nX: y"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(replaced).doesNotContain(" ").doesNotContain("\n");
    }

    @Test
    void errorsCarryTheRequestsCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()).header("Authorization", JwtTestTokens.admin())
                        .header("X-Correlation-Id", "corr-error-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId", is("corr-error-1")));
    }

    @Test
    void bodiesOver64KbAreRefusedWith413() throws Exception {
        Customer c = newCustomer();
        String huge = "{\"customerId\":\"" + c.getId() + "\",\"pad\":\"" + "x".repeat(70_000) + "\"}";
        mvc.perform(createOrder(JwtTestTokens.customer(c.getId()), newKey(), huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is("PAYLOAD_TOO_LARGE")));
    }

    @Test
    void securityHeadersAndNoStackTracesLeak() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Authorization", JwtTestTokens.admin()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("X-Frame-Options"))
                .andExpect(header().exists("Cache-Control"));
        String error = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{broken")
                .header("Authorization", JwtTestTokens.admin()).header("Idempotency-Key", newKey()))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(error).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("org.springframework");
    }

    @Test
    void configurationMatchesWhatTheReadmeDocuments() throws Exception {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(env.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests")).isEqualTo("true");
        assertThat(env.getProperty("oms.saga.max-attempts")).isEqualTo("5");
        assertThat(env.getProperty("oms.redis.enabled")).isEqualTo("false");
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getPoolName()).isEqualTo("order-db");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(3000);
    }

    @Test
    void opsMessagingEndpointIsAvailableToStaffAndNeverFailsWithoutKafka() throws Exception {
        mvc.perform(get("/api/v1/ops/messaging").header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", is("order-service")))
                .andExpect(jsonPath("$.consumerGroup", is("order-service")))
                .andExpect(jsonPath("$.topics[0].topic", is("oms.inventory.events.v1")))
                .andExpect(jsonPath("$.lagAvailable", is(false)))
                .andExpect(jsonPath("$.dltTopics[0]", containsString(".DLT")))
                .andExpect(jsonPath("$.outboxPending").isNumber());
    }
}
