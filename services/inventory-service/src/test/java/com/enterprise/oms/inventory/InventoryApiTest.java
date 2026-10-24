package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.testsupport.JsonAssertions;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Inventory views, stock adjustment, reservation listing, internal validate and ops endpoints over REST. */
class InventoryApiTest extends AbstractInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID customerId = UUID.fromString("c0000000-0000-0000-0000-000000000002");

    @Autowired ReservationService reservations;
    @Autowired ReservationLifecycleService lifecycle;

    private MvcResult call(MockHttpServletRequestBuilder request, String auth) throws Exception {
        return mvc.perform(auth == null ? request : request.header("Authorization", auth)).andReturn();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String auth, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), auth);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String adjustJson(int delta, String reason) {
        return "{\"delta\":" + delta + ",\"reason\":\"" + reason + "\"}";
    }

    // ---------------------------------------------------------------- inventory views

    @Test
    void inventoryViewsAreStaffOnly() throws Exception {
        UUID p = newProduct(7);
        for (String path : new String[]{"/api/v1/inventory", "/api/v1/inventory/" + p, "/api/v1/inventory/reservations",
                "/api/v1/inventory/" + p + "/movements"}) {
            assertThat(call(get(path), JwtTestTokens.customer(customerId)).getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(call(get(path), JwtTestTokens.service("order-service")).getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(call(get(path), null).getResponse().getStatus()).as(path).isEqualTo(401);
            assertThat(call(get(path), JwtTestTokens.operator()).getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(call(get(path), JwtTestTokens.admin()).getResponse().getStatus()).as(path).isEqualTo(200);
        }
    }

    @Test
    void inventoryOfOneProductShowsStockFieldsAndUnknownIs404() throws Exception {
        UUID p = newProduct(20);
        reservations.reserve(UUID.randomUUID(), List.of(item(p, 6)), null, "c", null);

        JsonNode v = JSON.readTree(body(call(get("/api/v1/inventory/" + p), JwtTestTokens.operator())));
        assertThat(v.get("onHand").asInt()).isEqualTo(20);
        assertThat(v.get("reserved").asInt()).isEqualTo(6);
        assertThat(v.get("available").asInt()).isEqualTo(14);

        MvcResult missing = call(get("/api/v1/inventory/" + UUID.randomUUID()), JwtTestTokens.operator());
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        JsonAssertions.of(body(missing)).isProblem(404, "NOT_FOUND");
    }

    @Test
    void inventoryListSupportsSkuAndLowStockFilters() throws Exception {
        UUID low = newProduct(2);
        UUID plenty = newProduct(500);
        String lowSku = sku(low);

        JsonNode bySku = JSON.readTree(body(call(get("/api/v1/inventory").param("sku", lowSku.toLowerCase()), JwtTestTokens.operator())));
        assertThat(bySku.get("content")).hasSize(1);
        assertThat(bySku.get("content").get(0).get("id").asText()).isEqualTo(low.toString());

        JsonNode lowStock = JSON.readTree(body(call(get("/api/v1/inventory").param("lowStock", "true").param("size", "100"),
                JwtTestTokens.operator())));
        List<String> ids = new ArrayList<>();
        lowStock.get("content").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).contains(low.toString()).doesNotContain(plenty.toString());
        JsonAssertions.of(body(call(get("/api/v1/inventory").param("size", "5"), JwtTestTokens.admin()))).isPage(5, 0);
    }

    // ---------------------------------------------------------------- stock adjustment

    @Test
    void operatorAdjustsStockAndTheMovementAndAuditTrailAreWritten() throws Exception {
        UUID p = newProduct(10);

        MvcResult r = send(put("/api/v1/inventory/" + p + "/stock"), JwtTestTokens.operator(), adjustJson(15, "cycle count correction"));

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        JsonAssertions.of(body(r)).hasInt("/onHand", 25).hasInt("/available", 25).hasText("/id", p.toString());
        assertThat(onHand(p)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type = 'ADJUSTMENT' "
                + "AND on_hand_delta = 15 AND on_hand_after = 25 AND actor = 'operator-test'", Integer.class, p)).isEqualTo(1);
        MvcResult audit = call(get("/api/v1/ops/audit").param("entityType", "Stock").param("entityId", p.toString()),
                JwtTestTokens.operator());
        JsonAssertions.of(body(audit)).hasInt("/totalElements", 1).hasText("/content/0/action", "STOCK_ADJUSTED")
                .hasText("/content/0/actor", "operator-test");
        JsonNode movements = JSON.readTree(body(call(get("/api/v1/inventory/" + p + "/movements"), JwtTestTokens.admin())));
        assertThat(movements.get("content")).hasSize(1);
        assertThat(movements.get("content").get(0).get("reason").asText()).isEqualTo("cycle count correction");
    }

    @Test
    void adjustingBelowTheReservedQuantityIsRejectedWith409AndChangesNothing() throws Exception {
        UUID p = newProduct(10);
        reservations.reserve(UUID.randomUUID(), List.of(item(p, 8)), null, "c", null);

        MvcResult tooMuch = send(put("/api/v1/inventory/" + p + "/stock"), JwtTestTokens.admin(), adjustJson(-3, "write off damaged"));
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(409);
        JsonAssertions.of(body(tooMuch)).isProblem(409, "CONFLICT");
        assertThat(onHand(p)).isEqualTo(10);

        MvcResult exactlyReserved = send(put("/api/v1/inventory/" + p + "/stock"), JwtTestTokens.admin(), adjustJson(-2, "write off damaged"));
        assertThat(exactlyReserved.getResponse().getStatus()).as("on_hand may equal reserved").isEqualTo(200);
        assertThat(onHand(p)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type = 'ADJUSTMENT'",
                Integer.class, p)).isEqualTo(1);
    }

    @Test
    void adjustValidationAndAuthorization() throws Exception {
        UUID p = newProduct(10);
        String path = "/api/v1/inventory/" + p + "/stock";
        assertThat(send(put(path), JwtTestTokens.customer(customerId), adjustJson(1, "customer cannot")).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(put(path), JwtTestTokens.service("order-service"), adjustJson(1, "service cannot")).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(put(path), null, adjustJson(1, "anonymous")).getResponse().getStatus()).isEqualTo(401);
        for (String bad : new String[]{adjustJson(0, "zero delta"), "{\"delta\":1}", "{\"delta\":1,\"reason\":\"\"}",
                "{\"reason\":\"no delta\"}", adjustJson(2_000_000, "too big"), "{\"delta\":1.5,\"reason\":\"fraction\"}"}) {
            MvcResult r = send(put(path), JwtTestTokens.operator(), bad);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            JsonAssertions.of(body(r)).isProblem(400, "VALIDATION_FAILED");
        }
        assertThat(send(put("/api/v1/inventory/" + UUID.randomUUID() + "/stock"), JwtTestTokens.operator(), adjustJson(1, "unknown product"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(onHand(p)).isEqualTo(10);
    }

    @Test
    void concurrentAdjustmentsAreAllAppliedThroughVersionRetries() throws Exception {
        UUID p = newProduct(100);
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> task = () -> {
                    go.await();
                    return send(put("/api/v1/inventory/" + p + "/stock"), JwtTestTokens.operator(),
                            adjustJson(1, "concurrent restock")).getResponse().getStatus();
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(onHand(p)).as("no lost update").isEqualTo(100 + threads);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type = 'ADJUSTMENT'",
                Integer.class, p)).isEqualTo(threads);
    }

    @Test
    void adjustingWhileReservationsRunNeverBreaksTheReservedInvariant() throws Exception {
        UUID p = newProduct(50);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    reservations.reserve(UUID.randomUUID(), List.of(item(p, 5)), null, "c", null);
                    return null;
                }));
            }
            for (int i = 0; i < 2; i++) {
                all.add(pool.submit(() -> {
                    go.await();
                    // may succeed or lose with 409 depending on timing, but must never corrupt the books
                    send(put("/api/v1/inventory/" + p + "/stock"), JwtTestTokens.operator(), adjustJson(-20, "shrinkage"));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : all) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(reserved(p)).isLessThanOrEqualTo(onHand(p));
        assertThat(onHand(p)).isGreaterThanOrEqualTo(0);
    }

    // ---------------------------------------------------------------- reservations listing

    @Test
    void reservationsAreListedWithItemsSkusAndFilters() throws Exception {
        UUID a = newProduct(10);
        UUID b = newProduct(10);
        UUID order = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ReservationOutcome first = reservations.reserve(order, List.of(item(a, 2), item(b, 3)), null, "c", null);
        reservations.reserve(other, List.of(item(a, 100)), null, "c", null);   // FAILED

        JsonNode byOrder = JSON.readTree(body(call(get("/api/v1/inventory/reservations").param("orderId", order.toString()),
                JwtTestTokens.operator())));
        assertThat(byOrder.get("content")).hasSize(1);
        JsonNode r = byOrder.get("content").get(0);
        assertThat(r.get("id").asText()).isEqualTo(first.reservationId().toString());
        assertThat(r.get("status").asText()).isEqualTo("RESERVED");
        assertThat(r.get("items")).hasSize(2);
        assertThat(r.get("items").get(0).get("sku").asText()).startsWith("T-");
        assertThat(r.has("expiresAt")).isTrue();
        assertThat(r.has("createdAt")).isTrue();

        JsonNode failed = JSON.readTree(body(call(get("/api/v1/inventory/reservations").param("orderId", other.toString())
                .param("status", "failed"), JwtTestTokens.admin())));
        assertThat(failed.get("content")).hasSize(1);
        assertThat(failed.get("content").get(0).get("failureReason").asText()).isEqualTo("INSUFFICIENT_STOCK");

        lifecycle.release(order, "CANCELLATION", false, "c", null);
        JsonNode released = JSON.readTree(body(call(get("/api/v1/inventory/reservations").param("orderId", order.toString())
                .param("status", "RELEASED"), JwtTestTokens.admin())));
        assertThat(released.get("content").get(0).get("releaseReason").asText()).isEqualTo("CANCELLATION");

        MvcResult bad = call(get("/api/v1/inventory/reservations").param("status", "NOPE"), JwtTestTokens.admin());
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        JsonAssertions.of(body(bad)).isProblem(400, "VALIDATION_FAILED");
        assertThat(call(get("/api/v1/inventory/reservations").param("orderId", "xyz"), JwtTestTokens.admin()).getResponse().getStatus()).isEqualTo(400);
        JsonAssertions.of(body(call(get("/api/v1/inventory/reservations").param("size", "3"), JwtTestTokens.admin()))).isPage(3, 0);
    }

    // ---------------------------------------------------------------- internal validate (SERVICE only)

    private String validateJson(Object... productAndQty) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < productAndQty.length; i += 2) {
            sb.append(i == 0 ? "" : ",").append("{\"productId\":\"").append(productAndQty[i]).append("\",\"quantity\":")
                    .append(productAndQty[i + 1]).append("}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void validateIsServiceOnly() throws Exception {
        UUID p = newProduct(10);
        String path = "/api/v1/internal/products/validate";
        String body = validateJson(p, 1);
        assertThat(send(post(path), JwtTestTokens.service("order-service"), body).getResponse().getStatus()).isEqualTo(200);
        for (String token : new String[]{JwtTestTokens.admin(), JwtTestTokens.operator(), JwtTestTokens.customer(customerId)}) {
            MvcResult r = send(post(path), token, body);
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            JsonAssertions.of(body(r)).isProblem(403, "FORBIDDEN");
        }
        assertThat(send(post(path), null, body).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void validateReportsEveryLineWithItsReason() throws Exception {
        UUID ok = newProduct(10);
        UUID shortOne = newProduct(2);
        UUID inactive = newProduct(10, false);
        UUID unknown = UUID.randomUUID();

        MvcResult r = send(post("/api/v1/internal/products/validate"), JwtTestTokens.service("order-service"),
                validateJson(ok, 3, shortOne, 5, inactive, 1, unknown, 1));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode v = JSON.readTree(body(r));
        assertThat(v.get("valid").asBoolean()).isFalse();
        JsonNode lines = v.get("lines");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).get("ok").asBoolean()).isTrue();
        assertThat(lines.get(0).get("sku").asText()).isEqualTo(sku(ok));
        assertThat(lines.get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(lines.get(0).get("available").asInt()).isEqualTo(10);
        assertThat(lines.get(0).get("reason").isNull()).isTrue();
        assertThat(lines.get(1).get("reason").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(lines.get(1).get("available").asInt()).isEqualTo(2);
        assertThat(lines.get(2).get("reason").asText()).isEqualTo("INACTIVE");
        assertThat(lines.get(2).get("active").asBoolean()).isFalse();
        assertThat(lines.get(3).get("reason").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void validateIsValidWhenEverythingFitsAndSumsDuplicateLines() throws Exception {
        UUID p = newProduct(5);
        MvcResult fits = send(post("/api/v1/internal/products/validate"), JwtTestTokens.service("order-service"), validateJson(p, 5));
        JsonAssertions.of(body(fits)).hasBoolean("/valid", true);
        MvcResult duplicates = send(post("/api/v1/internal/products/validate"), JwtTestTokens.service("order-service"),
                validateJson(p, 3, p, 3));
        JsonAssertions.of(body(duplicates)).hasBoolean("/valid", false);
    }

    @Test
    void validateRejectsMalformedRequests() throws Exception {
        String auth = JwtTestTokens.service("order-service");
        UUID p = newProduct(5);
        for (String bad : new String[]{"{\"items\":[]}", "{}", validateJson(p, 0), validateJson(p, 1001),
                "{\"items\":[{\"quantity\":1}]}", "{\"items\":[{\"productId\":\"nope\",\"quantity\":1}]}"}) {
            MvcResult r = send(post("/api/v1/internal/products/validate"), auth, bad);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            JsonAssertions.of(body(r)).isProblem(400, "VALIDATION_FAILED");
        }
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"productId\":\"").append(UUID.randomUUID()).append("\",\"quantity\":1}");
        }
        assertThat(send(post("/api/v1/internal/products/validate"), auth, many.append("]}").toString()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void oversizedBodiesAreRejectedWith413() throws Exception {
        String huge = "{\"items\":[],\"pad\":\"" + "x".repeat(70_000) + "\"}";
        MvcResult r = send(post("/api/v1/internal/products/validate"), JwtTestTokens.service("order-service"), huge);
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
        JsonAssertions.of(body(r)).isProblem(413, "PAYLOAD_TOO_LARGE");
    }

    // ---------------------------------------------------------------- ops endpoints (provided by platform-common)

    @Test
    void opsEndpointsAreStaffOnlyAndNeverFail() throws Exception {
        assertThat(call(get("/api/v1/ops/messaging"), JwtTestTokens.customer(customerId)).getResponse().getStatus()).isEqualTo(403);
        MvcResult messaging = call(get("/api/v1/ops/messaging"), JwtTestTokens.operator());
        assertThat(messaging.getResponse().getStatus()).isEqualTo(200);
        JsonAssertions.of(body(messaging)).hasText("/service", "inventory-service").hasField("/topics").hasField("/dltTopics");
        JsonNode topics = JSON.readTree(body(messaging)).get("topics");
        List<String> names = new ArrayList<>();
        topics.forEach(t -> names.add(t.get("topic").asText()));
        assertThat(names).containsExactlyInAnyOrder("oms.inventory.commands.v1", "oms.order.events.v1", "oms.fulfillment.events.v1");
        assertThat(call(get("/api/v1/ops/audit"), JwtTestTokens.admin()).getResponse().getStatus()).isEqualTo(200);
    }
}
