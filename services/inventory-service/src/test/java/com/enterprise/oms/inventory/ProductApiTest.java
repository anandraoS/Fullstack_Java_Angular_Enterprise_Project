package com.enterprise.oms.inventory;

import com.enterprise.oms.testsupport.JsonAssertions;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Product catalog REST contract via MockMvc with REAL JWTs: authz matrix, validation, problem shapes, paging, filters. */
class ProductApiTest extends AbstractInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID customerId = UUID.fromString("c0000000-0000-0000-0000-000000000001");

    private MvcResult call(MockHttpServletRequestBuilder request, String auth) throws Exception {
        return mvc.perform(auth == null ? request : request.header("Authorization", auth)).andReturn();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String auth, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body), auth);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String createJson(String sku, String category, String price, int stock) {
        return """
                {"sku":"%s","name":"Widget %s","description":"desc","category":"%s","price":%s,"initialStock":%d}
                """.formatted(sku, sku, category, price, stock);
    }

    private JsonNode createProduct(String sku, String category, int stock) throws Exception {
        MvcResult r = send(post("/api/v1/products"), JwtTestTokens.admin(), createJson(sku, category, "12.50", stock));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        return JSON.readTree(body(r));
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ---------------------------------------------------------------- authentication / authorization matrix

    @Test
    void unauthenticatedAndInvalidTokensAreRejectedWithProblemJson() throws Exception {
        MvcResult none = call(get("/api/v1/products"), null);
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        JsonAssertions.of(body(none)).isProblem(401, "UNAUTHENTICATED");
        assertThat(call(get("/api/v1/products"), JwtTestTokens.expired()).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/api/v1/products"), JwtTestTokens.signedWithWrongSecret()).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get("/api/v1/products"), JwtTestTokens.wrongAudience()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void writeEndpointsAreAdminOnly() throws Exception {
        String sku = "AUTH-" + uniq();
        for (String token : new String[]{JwtTestTokens.customer(customerId), JwtTestTokens.operator(),
                JwtTestTokens.service("order-service")}) {
            MvcResult r = send(post("/api/v1/products"), token, createJson(sku, "Auth", "1.00", 1));
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            JsonAssertions.of(body(r)).isProblem(403, "FORBIDDEN");
        }
        UUID id = newProduct(5);
        String update = "{\"name\":\"n\",\"category\":\"c\",\"price\":1.00}";
        assertThat(send(put("/api/v1/products/" + id), JwtTestTokens.operator(), update).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(delete("/api/v1/products/" + id), JwtTestTokens.operator()).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(delete("/api/v1/products/" + id), JwtTestTokens.customer(customerId)).getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void serviceTokensCannotReadTheCustomerFacingCatalog() throws Exception {
        assertThat(call(get("/api/v1/products"), JwtTestTokens.service("order-service")).getResponse().getStatus()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- role-shaped responses

    @Test
    void customersSeeAvailableOnlyAndNoInactiveProducts() throws Exception {
        String category = "Shape-" + uniq();
        JsonNode active = createProduct("SH-A-" + uniq(), category, 40);
        JsonNode inactive = createProduct("SH-I-" + uniq(), category, 10);
        assertThat(call(delete("/api/v1/products/" + inactive.get("id").asText()), JwtTestTokens.admin())
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult asCustomer = call(get("/api/v1/products").param("category", category), JwtTestTokens.customer(customerId));
        JsonNode page = JSON.readTree(body(asCustomer));
        assertThat(page.get("content")).hasSize(1);
        JsonNode p = page.get("content").get(0);
        assertThat(p.get("id").asText()).isEqualTo(active.get("id").asText());
        assertThat(p.get("available").asInt()).isEqualTo(40);
        assertThat(p.has("onHand")).isFalse();
        assertThat(p.has("reserved")).isFalse();
        assertThat(p.has("version")).isFalse();
        assertThat(body(asCustomer)).as("money keeps scale 2").contains("\"price\":12.50");
        assertThat(p.get("currency").asText()).isEqualTo("USD");

        JsonNode staffPage = JSON.readTree(body(call(get("/api/v1/products").param("category", category), JwtTestTokens.operator())));
        assertThat(staffPage.get("content")).hasSize(2);
        JsonNode s = staffPage.get("content").get(0);
        assertThat(s.get("onHand").asInt()).isGreaterThanOrEqualTo(10);
        assertThat(s.has("reserved")).isTrue();
        assertThat(s.has("version")).isTrue();
    }

    @Test
    void customerGetOfAnInactiveProductIs404ButStaffCanStillSeeIt() throws Exception {
        JsonNode created = createProduct("INACT-" + uniq(), "Inact", 3);
        String id = created.get("id").asText();
        call(delete("/api/v1/products/" + id), JwtTestTokens.admin());

        MvcResult customer = call(get("/api/v1/products/" + id), JwtTestTokens.customer(customerId));
        assertThat(customer.getResponse().getStatus()).isEqualTo(404);
        JsonAssertions.of(body(customer)).isProblem(404, "NOT_FOUND");
        MvcResult operator = call(get("/api/v1/products/" + id), JwtTestTokens.operator());
        assertThat(operator.getResponse().getStatus()).isEqualTo(200);
        JsonAssertions.of(body(operator)).hasBoolean("/active", false);
    }

    // ---------------------------------------------------------------- problem shapes

    @Test
    void unknownAndMalformedIdsProduceProblemJson() throws Exception {
        MvcResult missing = call(get("/api/v1/products/" + UUID.randomUUID()), JwtTestTokens.customer(customerId));
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        JsonAssertions.of(body(missing)).isProblem(404, "NOT_FOUND").hasField("/instance");
        assertThat(missing.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(missing.getResponse().getHeader("X-Correlation-Id")).isNotBlank();

        MvcResult malformed = call(get("/api/v1/products/not-a-uuid"), JwtTestTokens.customer(customerId));
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(malformed)).doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void createValidationReportsEveryFieldError() throws Exception {
        MvcResult r = send(post("/api/v1/products"), JwtTestTokens.admin(),
                "{\"sku\":\"x\",\"name\":\"\",\"category\":\"\",\"price\":-1,\"initialStock\":-5}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonAssertions.of(body(r)).isProblem(400, "VALIDATION_FAILED");
        JsonNode errors = JSON.readTree(body(r)).get("errors");
        assertThat(errors).extracting(e -> e.get("field").asText())
                .contains("sku", "name", "category", "price", "initialStock");
    }

    @Test
    void malformedJsonAndWrongContentTypeAreClientErrors() throws Exception {
        assertThat(send(post("/api/v1/products"), JwtTestTokens.admin(), "{not json").getResponse().getStatus()).isEqualTo(400);
        MvcResult wrong = call(post("/api/v1/products").contentType(MediaType.TEXT_PLAIN).content("x"), JwtTestTokens.admin());
        assertThat(wrong.getResponse().getStatus()).isEqualTo(415);
        JsonAssertions.of(body(wrong)).isProblem(415, "UNSUPPORTED_MEDIA_TYPE");
    }

    // ---------------------------------------------------------------- CRUD

    @Test
    void adminCreatesUpdatesAndDeactivatesAProduct() throws Exception {
        String sku = "crud-" + uniq().toLowerCase();
        MvcResult created = send(post("/api/v1/products"), JwtTestTokens.admin(), createJson(sku, "Crud", "19.99", 25));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode p = JSON.readTree(body(created));
        String id = p.get("id").asText();
        assertThat(created.getResponse().getHeader("Location")).endsWith("/api/v1/products/" + id);
        assertThat(p.get("sku").asText()).isEqualTo(sku.toUpperCase());        // normalized
        assertThat(p.get("onHand").asInt()).isEqualTo(25);
        assertThat(p.get("available").asInt()).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type = 'ADJUSTMENT'",
                Integer.class, UUID.fromString(id))).isEqualTo(1);

        MvcResult updated = send(put("/api/v1/products/" + id), JwtTestTokens.admin(),
                "{\"name\":\"Renamed\",\"description\":\"new\",\"category\":\"Crud2\",\"price\":21.00,\"version\":" + p.get("version").asLong() + "}");
        assertThat(updated.getResponse().getStatus()).as(body(updated)).isEqualTo(200);
        JsonAssertions.of(body(updated)).hasText("/name", "Renamed").hasText("/category", "Crud2");
        assertThat(JSON.readTree(body(updated)).get("price").decimalValue()).isEqualByComparingTo("21.00");

        MvcResult deactivated = call(delete("/api/v1/products/" + id), JwtTestTokens.admin());
        JsonAssertions.of(body(deactivated)).hasBoolean("/active", false);
        assertThat(call(delete("/api/v1/products/" + id), JwtTestTokens.admin()).getResponse().getStatus())
                .as("deactivate is idempotent").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_type = 'Product' AND entity_id = ?",
                Integer.class, id)).isEqualTo(3);   // CREATED, UPDATED, DEACTIVATED
    }

    @Test
    void duplicateSkuIs409AndStaleVersionIs409() throws Exception {
        String sku = "DUP-" + uniq();
        JsonNode first = createProduct(sku, "Dup", 1);
        MvcResult dup = send(post("/api/v1/products"), JwtTestTokens.admin(), createJson(sku.toLowerCase(), "Dup", "1.00", 1));
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        JsonAssertions.of(body(dup)).isProblem(409, "CONFLICT");

        MvcResult stale = send(put("/api/v1/products/" + first.get("id").asText()), JwtTestTokens.admin(),
                "{\"name\":\"n\",\"category\":\"c\",\"price\":1.00,\"version\":" + (first.get("version").asLong() + 7) + "}");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonAssertions.of(body(stale)).isProblem(409, "CONFLICT");
    }

    @Test
    void updateOfAnUnknownProductIs404() throws Exception {
        MvcResult r = send(put("/api/v1/products/" + UUID.randomUUID()), JwtTestTokens.admin(),
                "{\"name\":\"n\",\"category\":\"c\",\"price\":1.00}");
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- search, filters, pagination

    @Test
    void searchFiltersByTextCategoryAndInStock() throws Exception {
        String category = "Find-" + uniq();
        String token = uniq();
        createProduct("FND-" + token + "-A", category, 5);
        createProduct("FND-" + token + "-B", category, 0);
        createProduct("OTHER-" + uniq(), category, 9);
        String auth = JwtTestTokens.customer(customerId);

        assertThat(count(call(get("/api/v1/products").param("category", category), auth))).isEqualTo(3);
        assertThat(count(call(get("/api/v1/products").param("category", category).param("q", "fnd-" + token), auth)))
                .as("q matches sku case-insensitively").isEqualTo(2);
        assertThat(count(call(get("/api/v1/products").param("category", category).param("inStock", "true"), auth))).isEqualTo(2);
        assertThat(count(call(get("/api/v1/products").param("category", category).param("inStock", "false"), auth))).isEqualTo(1);
        assertThat(count(call(get("/api/v1/products").param("q", "%"), auth)))
                .as("LIKE wildcards in user input are escaped, not interpreted").isZero();
    }

    private static int count(MvcResult r) throws Exception {
        return JSON.readTree(body(r)).get("content").size();
    }

    @Test
    void paginationEnvelopeAndSorting() throws Exception {
        String category = "Page-" + uniq();
        for (int i = 1; i <= 5; i++) {
            createProduct("PG-" + uniq(), category, i);
        }
        String auth = JwtTestTokens.operator();
        MvcResult first = call(get("/api/v1/products").param("category", category).param("page", "0").param("size", "2")
                .param("sort", "available,desc"), auth);
        JsonAssertions.of(body(first)).isPage(2, 0).hasInt("/totalElements", 5).hasInt("/totalPages", 3).arraySize("/content", 2);
        JsonNode content = JSON.readTree(body(first)).get("content");
        assertThat(content.get(0).get("available").asInt()).isEqualTo(5);
        assertThat(content.get(1).get("available").asInt()).isEqualTo(4);
        MvcResult last = call(get("/api/v1/products").param("category", category).param("page", "2").param("size", "2")
                .param("sort", "available,desc"), auth);
        JsonAssertions.of(body(last)).isPage(2, 2).arraySize("/content", 1);
        MvcResult beyond = call(get("/api/v1/products").param("category", category).param("page", "9").param("size", "2"), auth);
        JsonAssertions.of(body(beyond)).arraySize("/content", 0);
    }

    @Test
    void invalidPagingParametersAreRejectedNotClamped() throws Exception {
        String auth = JwtTestTokens.customer(customerId);
        for (String[] bad : new String[][]{{"size", "101"}, {"size", "0"}, {"page", "-1"}, {"sort", "password,asc"}, {"sort", "name,sideways"}}) {
            MvcResult r = call(get("/api/v1/products").param(bad[0], bad[1]), auth);
            assertThat(r.getResponse().getStatus()).as(bad[0] + "=" + bad[1]).isEqualTo(400);
            JsonAssertions.of(body(r)).isProblem(400, "VALIDATION_FAILED");
        }
        MvcResult tooLong = call(get("/api/v1/products").param("q", "x".repeat(101)), auth);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void sqlInjectionAttemptsInFiltersAreJustLiterals() throws Exception {
        MvcResult r = call(get("/api/v1/products").param("q", "x' OR '1'='1").param("category", "'; DROP TABLE products;--"),
                JwtTestTokens.customer(customerId));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(count(r)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isPositive();
    }

    @Test
    void responsesCarryCorrelationIdAndSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/products").header("Authorization", JwtTestTokens.admin()).header("X-Correlation-Id", "it-corr-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "it-corr-123"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
