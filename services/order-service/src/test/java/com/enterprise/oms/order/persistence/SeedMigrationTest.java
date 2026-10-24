package com.enterprise.oms.order.persistence;

import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the `dev` profile adds: Flyway V1000 seed with the contract's fixed customers. */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/common,classpath:db/migration,classpath:db/seed")
class SeedMigrationTest extends AbstractOrderSpringTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void theThreeContractCustomersExistWithFixedIds() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1000' and success = true",
                Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/customers/{id}", "c0000000-0000-0000-0000-000000000001").header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Acme Industrial Supply")))
                .andExpect(jsonPath("$.email", is("procurement@acme.example.test")));
        mvc.perform(get("/api/v1/customers/{id}", "c0000000-0000-0000-0000-000000000002").header("Authorization", JwtTestTokens.operator()))
                .andExpect(jsonPath("$.name", is("Globex Logistics")));
        mvc.perform(get("/api/v1/customers/{id}", "c0000000-0000-0000-0000-000000000003").header("Authorization", JwtTestTokens.operator()))
                .andExpect(jsonPath("$.email", is("ops@fail.example.test")));
    }

    @Test
    void seededCustomerCanReadOnlyThemselvesAndPlaceOrders() throws Exception {
        java.util.UUID acme = java.util.UUID.fromString("c0000000-0000-0000-0000-000000000001");
        java.util.UUID globex = java.util.UUID.fromString("c0000000-0000-0000-0000-000000000002");
        mvc.perform(get("/api/v1/customers/{id}", acme).header("Authorization", JwtTestTokens.customer(acme)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/{id}", globex).header("Authorization", JwtTestTokens.customer(acme)))
                .andExpect(status().isNotFound());
        var product = newProduct("2.00", 10);
        mvc.perform(createOrder(JwtTestTokens.customer(acme), newKey(), orderJson(acme, product.id(), 1)))
                .andExpect(status().isAccepted());
    }
}
