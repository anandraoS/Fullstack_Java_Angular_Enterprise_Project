package com.enterprise.oms.order.api;

import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerApiTest extends AbstractOrderSpringTest {

    private static String body(String name, String email, String tier) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"" + (tier == null ? "" : ",\"tier\":\"" + tier + "\"") + "}";
    }

    private static String uniqueEmail() {
        return "u-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    @Test
    void operatorAndAdminCreateCustomersWith201AndLocation() throws Exception {
        String email = uniqueEmail().toUpperCase();
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Umbrella Corp", email, "GOLD")).header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/customers/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.name", is("Umbrella Corp")))
                .andExpect(jsonPath("$.email", is(email.toLowerCase())))   // stored normalized
                .andExpect(jsonPath("$.tier", is("GOLD")))
                .andExpect(jsonPath("$.createdAt").exists());
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Soylent", uniqueEmail(), null)).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier", is("STANDARD")));
    }

    @Test
    void duplicateEmailIs409ConflictCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content(body("A", email, null))
                .header("Authorization", JwtTestTokens.admin())).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("B", email.toUpperCase(), null)).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONFLICT")));
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "not-an-email", null)).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItems("name", "email")));
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("x".repeat(201), uniqueEmail(), null)).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Ok", uniqueEmail(), "DIAMOND")).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotCreateCustomers() throws Exception {
        Customer c = newCustomer();
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Evil", uniqueEmail(), null)).header("Authorization", JwtTestTokens.customer(c.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
    }

    @Test
    void customerReadsSelfOnlyOthersAre404() throws Exception {
        Customer me = newCustomer();
        Customer other = newCustomer();
        mvc.perform(get("/api/v1/customers/{id}", me.getId()).header("Authorization", JwtTestTokens.customer(me.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id", is(me.getId().toString())))
                .andExpect(jsonPath("$.email", is(me.getEmail())));
        mvc.perform(get("/api/v1/customers/{id}", other.getId()).header("Authorization", JwtTestTokens.customer(me.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/customers/{id}", other.getId()).header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/{id}", UUID.randomUUID()).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingIsStaffOnlyAndSupportsSearchAndPaging() throws Exception {
        String marker = "Zzmark" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                    .content(body(marker + " " + i, uniqueEmail(), null)).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/customers").param("q", marker.toLowerCase()).param("size", "2")
                        .header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.content[0].name", is(marker + " 0")));
        mvc.perform(get("/api/v1/customers").param("sort", "name,desc").param("q", marker)
                        .header("Authorization", JwtTestTokens.admin()))
                .andExpect(jsonPath("$.content[0].name", is(marker + " 2")));
        Customer c = newCustomer();
        mvc.perform(get("/api/v1/customers").header("Authorization", JwtTestTokens.customer(c.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/customers").param("size", "500").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers").param("sort", "email,asc").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk());
        assertThat(customers.count()).isGreaterThanOrEqualTo(4);
    }
}
