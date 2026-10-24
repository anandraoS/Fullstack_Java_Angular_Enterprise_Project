package com.enterprise.oms.order.api;

import com.enterprise.oms.common.security.Roles;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authentication failures, the role matrix, and privilege-escalation attempts. */
class OrderSecurityTest extends AbstractOrderSpringTest {

    private Customer alice;
    private Customer mallory;
    private StubInventoryClient.Product widget;
    private UUID aliceOrder;

    @BeforeEach
    void fixtures() throws Exception {
        alice = newCustomer();
        mallory = newCustomer();
        widget = inventory.addProduct("SKU-SEC-" + UUID.randomUUID().toString().substring(0, 6), "5.00", 50);
        aliceOrder = placeOrder(alice, widget, 1);
    }

    // ---- authentication ------------------------------------------------------------------------------------------

    @Test
    void noTokenIs401ProblemJsonWithBearerChallenge() throws Exception {
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    void expiredForgedAndWrongAudienceTokensAreAll401WithoutSayingWhy() throws Exception {
        String expired = JwtTestTokens.expired();
        String forged = JwtTestTokens.signedWithWrongSecret();
        String wrongAudience = JwtTestTokens.wrongAudience();
        for (String bad : List.of(expired, forged, wrongAudience)) {
            mvc.perform(get("/api/v1/orders/{id}", aliceOrder).header("Authorization", bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                    .andExpect(jsonPath("$.detail", is("Missing, invalid or expired access token")));
        }
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedPayloadAndAlgNoneTokensAreRejected() throws Exception {
        // a CUSTOMER token whose payload is edited to claim ADMIN: the signature no longer matches
        String real = JwtTestTokens.customer(alice.getId()).substring("Bearer ".length());
        String[] parts = real.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ADMIN");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());

        // unsigned token with alg=none
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"root\",\"roles\":[\"ADMIN\"],\"iss\":\"oms-dev-issuer\","
                + "\"aud\":\"oms-api\",\"exp\":4102444800}").getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", "Bearer " + header + "." + body + "."))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").header("Authorization", "Basic YWRtaW46YWRtaW4="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndApiDocsArePublicEverythingElseIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ops/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/no-such-thing")).andExpect(status().isUnauthorized());
    }

    // ---- role matrix ---------------------------------------------------------------------------------------------

    private record Endpoint(HttpMethod method, String path, boolean customer, boolean operator, boolean admin, boolean service) {
    }

    private MockHttpServletRequestBuilder request(Endpoint e, String token) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(e.method(), e.path().replace("{id}", aliceOrder.toString()))
                .header("Authorization", token);
        if (e.method() == HttpMethod.POST) {
            b.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return b;
    }

    @Test
    void roleMatrixOfEveryEndpointGroup() throws Exception {
        // customer / operator / admin / service allowed? (true = anything but 401/403, false = exactly 403)
        List<Endpoint> endpoints = List.of(
                new Endpoint(HttpMethod.GET, "/api/v1/orders", true, true, true, false),
                new Endpoint(HttpMethod.GET, "/api/v1/orders/{id}/history", false, true, true, false),
                new Endpoint(HttpMethod.GET, "/api/v1/customers", false, true, true, false),
                new Endpoint(HttpMethod.POST, "/api/v1/customers", false, true, true, false),
                new Endpoint(HttpMethod.GET, "/api/v1/admin/orders/failed", false, true, true, false),
                new Endpoint(HttpMethod.POST, "/api/v1/admin/orders/{id}/retry", false, true, true, false),
                new Endpoint(HttpMethod.GET, "/api/v1/admin/saga/{id}", false, true, true, false),
                new Endpoint(HttpMethod.POST, "/api/v1/admin/reconciliation/run", false, false, true, false),
                new Endpoint(HttpMethod.GET, "/api/v1/ops/messaging", false, true, true, false),
                new Endpoint(HttpMethod.GET, "/api/v1/ops/audit", false, true, true, false));
        String customer = JwtTestTokens.customer(mallory.getId());   // mallory: not the owner of aliceOrder
        for (Endpoint e : endpoints) {
            assertRole(e, "CUSTOMER", customer, e.customer() && !e.path().contains("{id}"));
            assertRole(e, "OPERATOR", JwtTestTokens.operator(), e.operator());
            assertRole(e, "ADMIN", JwtTestTokens.admin(), e.admin());
            assertRole(e, "SERVICE", JwtTestTokens.service("inventory-service"), e.service());
        }
    }

    private void assertRole(Endpoint e, String role, String token, boolean allowed) throws Exception {
        int code = mvc.perform(request(e, token)).andReturn().getResponse().getStatus();
        if (allowed) {
            assertThat(code).as("%s %s as %s", e.method(), e.path(), role).isNotIn(401, 403);
        } else {
            assertThat(code).as("%s %s as %s", e.method(), e.path(), role).isIn(403, 404);
            if (!e.path().contains("{id}")) {
                assertThat(code).as("%s %s as %s", e.method(), e.path(), role).isEqualTo(403);
            }
        }
    }

    @Test
    void serviceTokensCannotUseCustomerFacingEndpoints() throws Exception {
        String svc = JwtTestTokens.service("inventory-service");
        mvc.perform(get("/api/v1/orders/{id}", aliceOrder).header("Authorization", svc)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(alice.getId(), widget.id(), 1)).header("Idempotency-Key", newKey())
                        .header("Authorization", svc))
                .andExpect(status().isForbidden());
    }

    // ---- privilege escalation attempts ---------------------------------------------------------------------------

    @Test
    void customerCannotReachOtherCustomersDataByAnyRoute() throws Exception {
        String evil = JwtTestTokens.customer(mallory.getId());
        mvc.perform(get("/api/v1/orders/{id}", aliceOrder).header("Authorization", evil)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/{id}/history", aliceOrder).header("Authorization", evil)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/{id}/cancel", aliceOrder).header("Authorization", evil)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/customers/{id}", alice.getId()).header("Authorization", evil)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders").param("customerId", alice.getId().toString()).header("Authorization", evil))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/orders").param("q", aliceOrder.toString()).header("Authorization", evil))
                .andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(alice.getId(), widget.id(), 1)).header("Idempotency-Key", newKey())
                        .header("Authorization", evil))
                .andExpect(status().isForbidden());
        assertThat(statusOf(aliceOrder).name()).isEqualTo("CREATED");
    }

    @Test
    void spoofedRoleHeadersParametersAndUnknownRoleClaimsGrantNothing() throws Exception {
        String customer = JwtTestTokens.customer(mallory.getId());
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", customer)
                        .header("X-User-Role", "ADMIN").header("X-Roles", "ADMIN").param("role", "ADMIN"))
                .andExpect(status().isForbidden());
        String superuser = JwtTestTokens.bearer("sneaky", List.of(Roles.CUSTOMER, "SUPERUSER", "ROLE_ADMIN"), mallory.getId());
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", superuser)).andExpect(status().isForbidden());
        String noRoles = JwtTestTokens.bearer("norole", List.of(), null);
        mvc.perform(get("/api/v1/orders").header("Authorization", noRoles)).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotChangeIdentityViaBodyOrHeaders() throws Exception {
        String evil = JwtTestTokens.customer(mallory.getId());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(alice.getId(), widget.id(), 1)).header("Idempotency-Key", newKey())
                        .header("X-Customer-Id", alice.getId().toString()).header("Authorization", evil))
                .andExpect(status().isForbidden());
        // mass assignment: unknown fields (status, version, id) in the body are ignored, never applied
        String body = "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"FULFILLED\",\"version\":99,\"totalAmount\":0.01,"
                + "\"customerId\":\"" + mallory.getId() + "\",\"items\":[{\"productId\":\"" + widget.id()
                + "\",\"quantity\":1,\"unitPrice\":0.01}],\"shippingAddress\":{\"line1\":\"1 A\",\"city\":\"C\",\"postalCode\":\"1\",\"country\":\"DE\"}}";
        mvc.perform(createOrder(evil, newKey(), body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("CREATED")))
                .andExpect(jsonPath("$.totalAmount", is(0.0)))
                .andExpect(jsonPath("$.items[0].unitPrice").value((Object) null));
    }

    @Test
    void opsAuditShowsOrderActionsToStaffOnly() throws Exception {
        mvc.perform(get("/api/v1/ops/audit").param("entityType", "Order").param("entityId", aliceOrder.toString())
                        .header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action", org.hamcrest.Matchers.hasItem("ORDER_CREATED")))
                .andExpect(jsonPath("$.content[0].entityType", is("Order")));
        mvc.perform(get("/api/v1/ops/audit").header("Authorization", JwtTestTokens.customer(alice.getId())))
                .andExpect(status().isForbidden());
    }
}
