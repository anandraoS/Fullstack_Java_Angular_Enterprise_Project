package com.enterprise.oms.inventory;

import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Probes, metrics names (contract section 7 + inventory metrics), http histograms, OpenAPI document. */
class ActuatorAndDocsTest extends AbstractInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired Environment env;
    @Autowired com.enterprise.oms.inventory.service.ReservationService reservations;

    @Test
    void probesAreOpenAndUp() throws Exception {
        for (String probe : new String[]{"/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health"}) {
            var r = mvc.perform(get(probe)).andReturn().getResponse();
            assertThat(r.getStatus()).as(probe).isEqualTo(200);
            assertThat(JSON.readTree(r.getContentAsString()).get("status").asText()).isEqualTo("UP");
        }
    }

    @Autowired io.micrometer.core.instrument.MeterRegistry registry;

    @Test
    void businessMetricsAndHttpLatencyHistogramsAreRegistered() throws Exception {
        UUID p = newProduct(1);
        reservations.reserve(UUID.randomUUID(), List.of(item(p, 1)), null, "c", null);
        reservations.reserve(UUID.randomUUID(), List.of(item(p, 1)), null, "c", null);   // fails: stock 1
        mvc.perform(get("/api/v1/products").header("Authorization", JwtTestTokens.admin()));

        assertThat(registry.get("oms.inventory.reservations").tag("outcome", "reserved").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("oms.inventory.reservations").tag("outcome", "failed").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.find("oms.inventory.reservation.conflicts").counter()).isNotNull();
        assertThat(registry.find("oms.inventory.reservation.duration").tag("strategy", "atomic").timer()).isNotNull();
        // oms.events.* meters are created on first use; InventoryKafkaIT asserts them in the real Prometheus scrape
        assertThat(registry.find("oms.outbox.pending").meters()).as("oms.outbox.pending").isNotEmpty();
        var http = registry.find("http.server.requests").timer();
        assertThat(http).as("http.server.requests recorded").isNotNull();
        // the exporter is off in plain Spring Boot tests (SimpleMeterRegistry has no buckets): the configuration is asserted here,
        // the real _bucket series in the Prometheus scrape is asserted in InventoryKafkaIT
        assertThat(env.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests")).isEqualTo("true");
        assertThat(http.getId().getTag("service")).isEqualTo("inventory-service");
    }

    @Test
    void openApiDocumentDescribesTheInventoryApiAndIsExportedForSpecs() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonNode doc = JSON.readTree(json);
        JsonNode paths = doc.get("paths");
        assertThat(paths.has("/api/v1/products")).isTrue();
        assertThat(paths.has("/api/v1/products/{id}")).isTrue();
        assertThat(paths.has("/api/v1/inventory")).isTrue();
        assertThat(paths.has("/api/v1/inventory/{productId}/stock")).isTrue();
        assertThat(paths.has("/api/v1/inventory/reservations")).isTrue();
        assertThat(paths.has("/api/v1/internal/products/validate")).isTrue();
        // exported next to the build output; copied to specs/openapi/inventory-service.json by the repo owner
        Path out = Path.of("target", "openapi", "inventory-service.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
    }

    @Test
    void hikariTuningFromTheConfigurationIsApplied(@Value("${spring.datasource.hikari.pool-name}") String poolName) {
        assertThat(poolName).isEqualTo("inventory-hikari");
        assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("3000");
        assertThat(env.getProperty("oms.inventory.reservation-strategy")).isEqualTo("atomic");
        for (String meter : new String[]{"hikaricp.connections.active", "hikaricp.connections.pending", "hikaricp.connections.timeout",
                "hikaricp.connections.acquire", "hikaricp.connections.usage", "hikaricp.connections.max"}) {
            assertThat(registry.find(meter).tag("pool", "inventory-hikari").meter()).as(meter).isNotNull();
        }
    }
}
