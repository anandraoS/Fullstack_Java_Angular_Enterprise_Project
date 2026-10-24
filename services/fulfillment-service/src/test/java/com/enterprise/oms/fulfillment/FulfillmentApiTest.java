package com.enterprise.oms.fulfillment;

import com.enterprise.oms.fulfillment.domain.Fulfillment;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST layer: authorization matrix (CUSTOMER not exposed), validation, problem+json shapes, retry rules. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FulfillmentApiTest {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired FulfillmentRepository repository;

    private Fulfillment seed(FulfillmentStatus target) {
        Fulfillment f = new Fulfillment(UUID.randomUUID(), UUID.randomUUID(), "1 Main", "Springfield", "1", "US", 1,
                "corr-api", UUID.randomUUID(), Instant.now());
        if (target == FulfillmentStatus.FAILED) {
            f.transitionTo(FulfillmentStatus.FAILED, Instant.now());
            f.setFailureReason("boom");
        } else if (target == FulfillmentStatus.COMPLETED) {
            f.transitionTo(FulfillmentStatus.PICKING, Instant.now());
            f.transitionTo(FulfillmentStatus.PACKED, Instant.now());
            f.transitionTo(FulfillmentStatus.SHIPPED, Instant.now());
            f.transitionTo(FulfillmentStatus.COMPLETED, Instant.now());
            f.setTrackingNumber("OMS-ABCDEF123456");
        }
        return repository.save(f);
    }

    private ResultActions as(String bearer, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void problem(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        problem(mvc.perform(get("/api/v1/fulfillments")), 401, "UNAUTHENTICATED");
        problem(mvc.perform(post("/api/v1/admin/fulfillments/" + UUID.randomUUID() + "/retry")), 401,
                "UNAUTHENTICATED");
    }

    @Test
    void customerIsForbiddenEverywhere() throws Exception {
        String customer = JwtTestTokens.customer(UUID.randomUUID());
        Fulfillment f = seed(FulfillmentStatus.FAILED);
        problem(as(customer, "/api/v1/fulfillments"), 403, "FORBIDDEN");
        problem(as(customer, "/api/v1/fulfillments/" + f.getId()), 403, "FORBIDDEN");
        problem(mvc.perform(post("/api/v1/admin/fulfillments/" + f.getId() + "/retry")
                .header(HttpHeaders.AUTHORIZATION, customer)), 403, "FORBIDDEN");
        problem(as(customer, "/api/v1/ops/messaging"), 403, "FORBIDDEN");
        problem(as(customer, "/api/v1/ops/audit"), 403, "FORBIDDEN");
        // the row is untouched
        org.assertj.core.api.Assertions.assertThat(repository.findById(f.getId()).orElseThrow().getStatus())
                .isEqualTo(FulfillmentStatus.FAILED);
    }

    @Test
    void invalidAndExpiredTokensAre401() throws Exception {
        problem(as(JwtTestTokens.expired(), "/api/v1/fulfillments"), 401, "UNAUTHENTICATED");
        problem(as(JwtTestTokens.signedWithWrongSecret(), "/api/v1/fulfillments"), 401, "UNAUTHENTICATED");
    }

    @Test
    void operatorListsGetsAndFiltersWithContractShape() throws Exception {
        Fulfillment f = seed(FulfillmentStatus.COMPLETED);
        as(JwtTestTokens.operator(), "/api/v1/fulfillments?orderId=" + f.getOrderId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(f.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].trackingNumber").value("OMS-ABCDEF123456"))
                .andExpect(jsonPath("$.content[0].events.length()").value(5))
                .andExpect(jsonPath("$.content[0].events[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].events[4].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].events[4].at").exists());
        as(JwtTestTokens.admin(), "/api/v1/fulfillments/" + f.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(f.getOrderId().toString()))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
        as(JwtTestTokens.operator(), "/api/v1/fulfillments?orderId=" + f.getOrderId() + "&status=FAILED")
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void validationErrorsAre400Problems() throws Exception {
        String op = JwtTestTokens.operator();
        problem(as(op, "/api/v1/fulfillments?status=NOPE"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/fulfillments?size=0"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/fulfillments?size=101"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/fulfillments?page=-1"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/fulfillments?sort=password,asc"), 400, "VALIDATION_FAILED");
        as(op, "/api/v1/fulfillments?sort=createdAt,asc&size=5").andExpect(status().isOk());
        as(op, "/api/v1/fulfillments?orderId=not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void unknownFulfillmentIs404AndEchoesCorrelationId() throws Exception {
        problem(as(JwtTestTokens.operator(), "/api/v1/fulfillments/" + UUID.randomUUID()), 404, "NOT_FOUND");
        mvc.perform(get("/api/v1/fulfillments/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.operator())
                        .header("X-Correlation-Id", "corr-echo-1"))
                .andExpect(header().string("X-Correlation-Id", "corr-echo-1"))
                .andExpect(jsonPath("$.correlationId").value("corr-echo-1"));
    }

    @Test
    void retryOfFailedWorksForOperatorAndAdminButNotForOtherStates() throws Exception {
        Fulfillment failed = seed(FulfillmentStatus.FAILED);
        mvc.perform(post("/api/v1/admin/fulfillments/" + failed.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.failureReason").doesNotExist());

        // now PENDING: a second retry is a state conflict
        problem(mvc.perform(post("/api/v1/admin/fulfillments/" + failed.getId() + "/retry")
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.admin())), 409, "INVALID_STATE_TRANSITION");
        Fulfillment done = seed(FulfillmentStatus.COMPLETED);
        problem(mvc.perform(post("/api/v1/admin/fulfillments/" + done.getId() + "/retry")
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.admin())), 409, "INVALID_STATE_TRANSITION");
        problem(mvc.perform(post("/api/v1/admin/fulfillments/" + UUID.randomUUID() + "/retry")
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.admin())), 404, "NOT_FOUND");
    }

    @Test
    void retryIsAuditedAndVisibleInOpsAudit() throws Exception {
        Fulfillment failed = seed(FulfillmentStatus.FAILED);
        mvc.perform(post("/api/v1/admin/fulfillments/" + failed.getId() + "/retry")
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.admin())).andExpect(status().isOk());
        as(JwtTestTokens.operator(), "/api/v1/ops/audit?entityType=Fulfillment&entityId=" + failed.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("RETRY_REQUESTED"))
                .andExpect(jsonPath("$.content[0].actor").value("admin-test"));
    }

    @Test
    void opsMessagingIsAvailableToOperatorsAndListsOwnTopic() throws Exception {
        as(JwtTestTokens.operator(), "/api/v1/ops/messaging")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("fulfillment-service"))
                .andExpect(jsonPath("$.consumerGroup").value("fulfillment-service"))
                .andExpect(jsonPath("$.topics[0].topic").value("oms.fulfillment.commands.v1"))
                .andExpect(jsonPath("$.dltTopics[0]").value("oms.fulfillment.commands.v1.DLT"));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/fulfillments']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/fulfillments/{id}/retry']").exists());
    }
}
