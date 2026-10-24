package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Profile {@code dev}: Flyway also runs classpath:db/seed (V1000) = contract section 9 catalog. Own database. */
@ActiveProfiles("dev")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:inventory_seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "oms.inventory.reservation-strategy=optimistic"})
class SeedDataTest extends AbstractInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired ReservationService reservations;

    private static UUID productId(int n) {
        return UUID.fromString("a0000000-0000-0000-0000-0000000010%02d".formatted(n));
    }

    @Autowired com.enterprise.oms.inventory.reservation.ReservationStrategies strategies;

    @Test
    void theStrategyIsSelectedByThePropertyAtStartup() {
        assertThat(strategies.active().name()).isEqualTo("optimistic");
    }

    @Test
    void twentyProductsWithTheContractSkusAndStock() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class)).contains("1000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(20);
        for (int n = 1; n <= 20; n++) {
            assertThat(jdbc.queryForObject("SELECT sku FROM products WHERE id = ?", String.class, productId(n)))
                    .isEqualTo("SKU-10%02d".formatted(n));
        }
        assertThat(onHand(productId(1))).isEqualTo(500);
        assertThat(onHand(productId(19))).isEqualTo(3);
        assertThat(onHand(productId(20))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE on_hand < 0 OR on_hand > 500 OR reserved <> 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE active = FALSE", Integer.class)).isZero();
    }

    @Test
    void catalogIsServedAndInStockFilteringFindsTheOutOfStockItem() throws Exception {
        String customer = JwtTestTokens.customer(UUID.fromString("c0000000-0000-0000-0000-000000000001"));
        JsonNode all = JSON.readTree(mvc.perform(get("/api/v1/products").param("size", "100").header("Authorization", customer))
                .andReturn().getResponse().getContentAsString());
        assertThat(all.get("totalElements").asInt()).isEqualTo(20);
        assertThat(all.get("content").get(0).get("sku").asText()).isEqualTo("SKU-1001");
        JsonNode out = JSON.readTree(mvc.perform(get("/api/v1/products").param("inStock", "false").header("Authorization", customer))
                .andReturn().getResponse().getContentAsString());
        assertThat(out.get("content")).hasSize(1);
        assertThat(out.get("content").get(0).get("sku").asText()).isEqualTo("SKU-1020");
    }

    @Test
    void theInsufficientStockDemoProductsBehaveAsDocumented() {
        ReservationOutcome out = reservations.reserve(UUID.randomUUID(), List.of(item(productId(19), 4), item(productId(1), 1)),
                null, "seed-demo", null);
        assertThat(out.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(out.shortages()).containsExactly(new Shortage(productId(19), 4, 3));
        assertThat(reserved(productId(1))).isZero();

        ReservationOutcome none = reservations.reserve(UUID.randomUUID(), List.of(item(productId(20), 1)), null, "seed-demo", null);
        assertThat(none.shortages()).containsExactly(new Shortage(productId(20), 1, 0));
    }
}
