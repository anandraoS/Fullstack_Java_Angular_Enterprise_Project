package com.enterprise.oms.order.api;

import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /orders is rate limited per caller (token bucket, in-memory). Separate context: tiny bucket, almost no refill. */
@TestPropertySource(properties = {"oms.order.rate-limit.capacity=3", "oms.order.rate-limit.refill-per-second=0.01"})
class RateLimitApiTest extends AbstractOrderSpringTest {

    @Test
    void theFourthOrderInABurstOfThreeIs429WithRetryAfter() throws Exception {
        Customer alice = newCustomer();
        StubInventoryClient.Product p = newProduct("1.00", 100);
        String token = JwtTestTokens.customer(alice.getId());
        for (int i = 0; i < 3; i++) {
            mvc.perform(createOrder(token, newKey(), orderJson(alice.getId(), p.id(), 1))).andExpect(status().isAccepted());
        }

        mvc.perform(createOrder(token, newKey(), orderJson(alice.getId(), p.id(), 1)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.code", is("RATE_LIMITED")))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.correlationId").exists());
        assertThat(orders.count((root, q, cb) -> cb.equal(root.get("customerId"), alice.getId()))).isEqualTo(3);
    }

    @Test
    void retryAfterIsAPositiveNumberOfSeconds() throws Exception {
        Customer alice = newCustomer();
        StubInventoryClient.Product p = newProduct("1.00", 100);
        String token = JwtTestTokens.customer(alice.getId());
        for (int i = 0; i < 3; i++) {
            mvc.perform(createOrder(token, newKey(), orderJson(alice.getId(), p.id(), 1)));
        }
        String retryAfter = mvc.perform(createOrder(token, newKey(), orderJson(alice.getId(), p.id(), 1)))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isGreaterThan(0);
    }

    @Test
    void limitsAreIndependentPerCallerAndReadsAreNotLimited() throws Exception {
        Customer alice = newCustomer();
        Customer bob = newCustomer();
        StubInventoryClient.Product p = newProduct("1.00", 100);
        for (int i = 0; i < 4; i++) {
            mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), newKey(), orderJson(alice.getId(), p.id(), 1)));
        }
        mvc.perform(createOrder(JwtTestTokens.customer(bob.getId()), newKey(), orderJson(bob.getId(), p.id(), 1)))
                .andExpect(status().isAccepted());
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/orders").header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void invalidRequestsDoNotConsumeTokensBecauseValidationRunsFirst() throws Exception {
        Customer alice = newCustomer();
        StubInventoryClient.Product p = newProduct("1.00", 100);
        String token = JwtTestTokens.customer(alice.getId());
        for (int i = 0; i < 6; i++) {
            mvc.perform(createOrder(token, newKey(), "{\"customerId\":\"" + alice.getId() + "\",\"items\":[]}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(createOrder(token, newKey(), orderJson(alice.getId(), p.id(), 1))).andExpect(status().isAccepted());
    }
}
