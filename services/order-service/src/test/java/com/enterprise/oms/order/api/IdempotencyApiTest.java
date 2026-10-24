package com.enterprise.oms.order.api;

import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.entity.IdempotencyKeyRecord;
import com.enterprise.oms.order.repository.IdempotencyKeyRepository;
import com.enterprise.oms.order.saga.IdempotencyKeyCleanupJob;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency-Key semantics of POST /orders: replay, mismatch, scope, failures, and a real concurrent race. */
class IdempotencyApiTest extends AbstractOrderSpringTest {

    @Autowired IdempotencyKeyRepository keys;
    @Autowired IdempotencyKeyCleanupJob cleanup;

    private Customer alice;
    private StubInventoryClient.Product widget;

    @BeforeEach
    void fixtures() {
        alice = newCustomer();
        widget = inventory.addProduct("SKU-IDEM-" + UUID.randomUUID().toString().substring(0, 6), "9.99", 100);
    }

    private long ordersOf(Customer c) {
        return orders.count((root, q, cb) -> cb.equal(root.get("customerId"), c.getId()));
    }

    @Test
    void replayWithSameKeyAndBodyReturnsTheOriginalResponseAndCreatesNothing() throws Exception {
        String key = newKey();
        String body = orderJson(alice.getId(), widget.id(), 2);
        String token = JwtTestTokens.customer(alice.getId());

        MvcResult first = mvc.perform(createOrder(token, key, body)).andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replay")).andReturn();
        MvcResult second = mvc.perform(createOrder(token, key, body)).andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true")).andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(second.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(ordersOf(alice)).isEqualTo(1);
    }

    @Test
    void replayReturnsTheSnapshotEvenAfterTheOrderMovedOn() throws Exception {
        String key = newKey();
        String body = orderJson(alice.getId(), widget.id(), 1);
        String token = JwtTestTokens.customer(alice.getId());
        MvcResult first = mvc.perform(createOrder(token, key, body)).andReturn();
        UUID id = UUID.fromString(mapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
        validate(id);
        assertThat(statusOf(id).name()).isEqualTo("VALIDATED");

        mvc.perform(createOrder(token, key, body)).andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.status", is("CREATED")))      // the ORIGINAL response, not the current state
                .andExpect(jsonPath("$.id", is(id.toString())));
    }

    @Test
    void equivalentBodiesWithDifferentFormattingAndLineOrderAreTheSameRequest() throws Exception {
        StubInventoryClient.Product other = inventory.addProduct("SKU-IDEM-B" + UUID.randomUUID().toString().substring(0, 5), "1.00", 10);
        String key = newKey();
        String token = JwtTestTokens.customer(alice.getId());
        String a = "{\"customerId\":\"" + alice.getId() + "\",\"items\":[{\"productId\":\"" + widget.id() + "\",\"quantity\":1},"
                + "{\"productId\":\"" + other.id() + "\",\"quantity\":2}],\"shippingAddress\":{\"line1\":\"1 A\",\"city\":\"Paris\","
                + "\"postalCode\":\"75001\",\"country\":\"FR\"}}";
        String b = "{ \"shippingAddress\" : {\"country\":\"FR\",\"postalCode\":\"75001\",\"city\":\"Paris\",\"line1\":\"1 A\"},\n"
                + "  \"items\" : [ {\"quantity\":2,\"productId\":\"" + other.id() + "\"}, {\"quantity\":1,\"productId\":\"" + widget.id() + "\"} ],\n"
                + "  \"customerId\" : \"" + alice.getId() + "\" }";
        mvc.perform(createOrder(token, key, a)).andExpect(status().isAccepted());
        mvc.perform(createOrder(token, key, b)).andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"));
        assertThat(ordersOf(alice)).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIs422AndCreatesNothing() throws Exception {
        String key = newKey();
        String token = JwtTestTokens.customer(alice.getId());
        mvc.perform(createOrder(token, key, orderJson(alice.getId(), widget.id(), 1))).andExpect(status().isAccepted());

        mvc.perform(createOrder(token, key, orderJson(alice.getId(), widget.id(), 2)))      // other quantity
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
        mvc.perform(createOrder(token, key, orderJson(alice.getId(), widget.id(), 1, "Madrid")))   // other address
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
        assertThat(ordersOf(alice)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerCustomer() throws Exception {
        Customer bob = newCustomer();
        String key = newKey();
        mvc.perform(createOrder(JwtTestTokens.operator(), key, orderJson(alice.getId(), widget.id(), 1)))
                .andExpect(status().isAccepted()).andExpect(header().doesNotExist("Idempotent-Replay"));
        mvc.perform(createOrder(JwtTestTokens.operator(), key, orderJson(bob.getId(), widget.id(), 1)))
                .andExpect(status().isAccepted()).andExpect(header().doesNotExist("Idempotent-Replay"));
        assertThat(ordersOf(alice)).isEqualTo(1);
        assertThat(ordersOf(bob)).isEqualTo(1);
    }

    @Test
    void aFailedRequestDoesNotBurnTheKey() throws Exception {
        String key = newKey();
        String token = JwtTestTokens.customer(alice.getId());
        String dup = "{\"customerId\":\"" + alice.getId() + "\",\"items\":[{\"productId\":\"" + widget.id() + "\",\"quantity\":1},"
                + "{\"productId\":\"" + widget.id() + "\",\"quantity\":1}],\"shippingAddress\":{\"line1\":\"1\",\"city\":\"C\",\"postalCode\":\"1\",\"country\":\"DE\"}}";
        mvc.perform(createOrder(token, key, dup)).andExpect(status().isBadRequest());

        mvc.perform(createOrder(token, key, orderJson(alice.getId(), widget.id(), 1)))
                .andExpect(status().isAccepted()).andExpect(header().doesNotExist("Idempotent-Replay"));
        assertThat(keys.findByScopeAndKey(alice.getId().toString(), key)).isPresent();
    }

    @Test
    void theKeyRecordStoresFingerprintAndResponseSnapshot() throws Exception {
        String key = newKey();
        MvcResult r = mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), key, orderJson(alice.getId(), widget.id(), 1)))
                .andReturn();
        IdempotencyKeyRecord rec = keys.findByScopeAndKey(alice.getId().toString(), key).orElseThrow();
        assertThat(rec.getRequestHash()).hasSize(64).matches("[0-9a-f]+");
        assertThat(rec.getResponseStatus()).isEqualTo(202);
        JsonNode snapshot = mapper.readTree(rec.getResponseBody());
        assertThat(snapshot.get("id").asText()).isEqualTo(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
        assertThat(rec.getOrderId().toString()).isEqualTo(snapshot.get("id").asText());
    }

    @Test
    void twoConcurrentSubmissionsOfTheSameRequestCreateExactlyOneOrder() throws Exception {
        String key = newKey();
        String body = orderJson(alice.getId(), widget.id(), 1);
        String token = JwtTestTokens.customer(alice.getId());

        List<MvcResult> results = race(2, () -> mvc.perform(createOrder(token, key, body)).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(202);
        long replays = results.stream().filter(r -> "true".equals(r.getResponse().getHeader("Idempotent-Replay"))).count();
        assertThat(replays).isEqualTo(1);
        assertThat(results.stream().map(r -> r.getResponse().getHeader("Location")).distinct()).hasSize(1);
        assertThat(ordersOf(alice)).isEqualTo(1);
        assertThat(keys.findByScopeAndKey(alice.getId().toString(), key)).isPresent();
    }

    @Test
    void eightConcurrentSubmissionsStillCreateExactlyOneOrder() throws Exception {
        String key = newKey();
        String body = orderJson(alice.getId(), widget.id(), 1);
        String token = JwtTestTokens.customer(alice.getId());

        List<MvcResult> results = race(8, () -> mvc.perform(createOrder(token, key, body)).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(202);
        assertThat(results.stream().filter(r -> "true".equals(r.getResponse().getHeader("Idempotent-Replay"))).count()).isEqualTo(7);
        assertThat(ordersOf(alice)).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsWithTheSameKeyButDifferentBodiesYieldOneOrderAndOne422() throws Exception {
        String key = newKey();
        String token = JwtTestTokens.customer(alice.getId());
        List<String> bodies = List.of(orderJson(alice.getId(), widget.id(), 1), orderJson(alice.getId(), widget.id(), 2));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (String b : bodies) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(createOrder(token, key, b)).andReturn();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                codes.add(f.get(60, TimeUnit.SECONDS).getResponse().getStatus());
            }
            assertThat(codes).containsExactlyInAnyOrder(202, 422);
            assertThat(ordersOf(alice)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupDeletesOnlyExpiredKeys() {
        keys.saveAndFlush(new IdempotencyKeyRecord("scope-x", "old-key-0001", "h".repeat(64), Instant.now().minusSeconds(3 * 24 * 3600)));
        keys.saveAndFlush(new IdempotencyKeyRecord("scope-x", "new-key-0001", "h".repeat(64), Instant.now()));

        int deleted = cleanup.cleanup();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(keys.findByScopeAndKey("scope-x", "old-key-0001")).isEmpty();
        assertThat(keys.findByScopeAndKey("scope-x", "new-key-0001")).isPresent();
    }

    /** Runs {@code n} copies of the call at the same instant (latch-released threads). */
    private <T> List<T> race(int n, Callable<T> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
