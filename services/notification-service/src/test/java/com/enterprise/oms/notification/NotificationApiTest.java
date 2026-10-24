package com.enterprise.oms.notification;

import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationChannel;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST layer: authorization (customer cross-access), forced filter, validation, retry rules, problem+json. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiTest {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository repository;

    private Notification seed(UUID customerId, UUID orderId, NotificationTemplate template, boolean failed,
                              boolean sent) {
        Notification n = new Notification(UUID.randomUUID(), customerId, orderId, NotificationChannel.EMAIL, template,
                template.subject(orderId, "T"), "corr-api", UUID.randomUUID(), Instant.now());
        if (failed) {
            n.markAttemptFailed("simulated", true, null);
        }
        if (sent) {
            n.markSent(Instant.now());
        }
        return repository.save(n);
    }

    private ResultActions as(String bearer, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions retry(String bearer, UUID id) throws Exception {
        return mvc.perform(post("/api/v1/admin/notifications/" + id + "/retry")
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void problem(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unauthenticatedAndBadTokensAre401() throws Exception {
        problem(mvc.perform(get("/api/v1/notifications")), 401, "UNAUTHENTICATED");
        problem(as(JwtTestTokens.expired(), "/api/v1/notifications"), 401, "UNAUTHENTICATED");
        problem(as(JwtTestTokens.signedWithWrongSecret(), "/api/v1/notifications"), 401, "UNAUTHENTICATED");
        problem(mvc.perform(post("/api/v1/admin/notifications/" + UUID.randomUUID() + "/retry")), 401,
                "UNAUTHENTICATED");
    }

    @Test
    void customerSeesOnlyOwnNotificationsAndCannotWidenTheFilter() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID aliceOrder = UUID.randomUUID();
        UUID bobOrder = UUID.randomUUID();
        Notification a1 = seed(alice, aliceOrder, NotificationTemplate.ORDER_CREATED, false, true);
        Notification b1 = seed(bob, bobOrder, NotificationTemplate.ORDER_CREATED, false, false);

        // no filter: forced to own customerId
        as(JwtTestTokens.customer(alice), "/api/v1/notifications?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.customerId != '" + alice + "')]").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + a1.getId() + "')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + b1.getId() + "')]").isEmpty());

        // asking for somebody else's customerId is overridden by the forced filter (never honoured)
        as(JwtTestTokens.customer(alice), "/api/v1/notifications?size=100&customerId=" + bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.customerId == '" + bob + "')]").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + b1.getId() + "')]").isEmpty());

        // asking for somebody else's order returns nothing, not 403/404 data leakage
        as(JwtTestTokens.customer(alice), "/api/v1/notifications?orderId=" + bobOrder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // own order works
        as(JwtTestTokens.customer(alice), "/api/v1/notifications?orderId=" + aliceOrder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SENT"));
    }

    @Test
    void customerTokenWithoutCustomerIdIsForbidden() throws Exception {
        String noId = JwtTestTokens.bearer("customer-x", java.util.List.of("CUSTOMER"), null);
        problem(as(noId, "/api/v1/notifications"), 403, "FORBIDDEN");
    }

    @Test
    void operatorAndAdminSeeEveryoneAndCanFilter() throws Exception {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID o1 = UUID.randomUUID();
        Notification n1 = seed(c1, o1, NotificationTemplate.ORDER_CONFIRMED, false, false);
        seed(c2, UUID.randomUUID(), NotificationTemplate.ORDER_CONFIRMED, true, false);

        as(JwtTestTokens.operator(), "/api/v1/notifications?customerId=" + c1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(n1.getId().toString()))
                .andExpect(jsonPath("$.content[0].customerId").value(c1.toString()))
                .andExpect(jsonPath("$.content[0].orderId").value(o1.toString()))
                .andExpect(jsonPath("$.content[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.content[0].template").value("ORDER_CONFIRMED"))
                .andExpect(jsonPath("$.content[0].subject").value(org.hamcrest.Matchers.containsString("confirmed")))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].attempts").value(0))
                .andExpect(jsonPath("$.content[0].correlationId").value("corr-api"))
                .andExpect(jsonPath("$.content[0].sourceEventId").exists())
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0].recipient").doesNotExist())
                .andExpect(jsonPath("$.content[0].email").doesNotExist());
        as(JwtTestTokens.admin(), "/api/v1/notifications?status=FAILED&customerId=" + c2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].lastError").value("simulated"));
        as(JwtTestTokens.admin(), "/api/v1/notifications?status=sent&customerId=" + c2)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void serviceTokensCannotReadNotifications() throws Exception {
        problem(as(JwtTestTokens.service("order-service"), "/api/v1/notifications"), 403, "FORBIDDEN");
    }

    @Test
    void validationErrorsAre400Problems() throws Exception {
        String op = JwtTestTokens.operator();
        problem(as(op, "/api/v1/notifications?status=NOPE"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/notifications?size=0"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/notifications?size=101"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/notifications?page=-1"), 400, "VALIDATION_FAILED");
        problem(as(op, "/api/v1/notifications?sort=recipient,asc"), 400, "VALIDATION_FAILED");
        as(op, "/api/v1/notifications?customerId=oops").andExpect(status().isBadRequest());
        as(op, "/api/v1/notifications?sort=createdAt,asc&size=5").andExpect(status().isOk());
    }

    @Test
    void retryIsStaffOnlyAndOnlyForFailedNotifications() throws Exception {
        UUID customer = UUID.randomUUID();
        Notification failed = seed(customer, UUID.randomUUID(), NotificationTemplate.ORDER_FAILED, true, false);

        // the owner cannot retry (admin endpoint)
        problem(retry(JwtTestTokens.customer(customer), failed.getId()), 403, "FORBIDDEN");
        assertThat(repository.findById(failed.getId()).orElseThrow().getStatus().name()).isEqualTo("FAILED");

        retry(JwtTestTokens.operator(), failed.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));

        // now PENDING: conflict
        problem(retry(JwtTestTokens.admin(), failed.getId()), 409, "INVALID_STATE_TRANSITION");
        Notification sent = seed(customer, UUID.randomUUID(), NotificationTemplate.ORDER_CREATED, false, true);
        problem(retry(JwtTestTokens.admin(), sent.getId()), 409, "INVALID_STATE_TRANSITION");
        problem(retry(JwtTestTokens.admin(), UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    void retryIsAuditedWithTheCallerSubject() throws Exception {
        Notification failed = seed(UUID.randomUUID(), UUID.randomUUID(), NotificationTemplate.ORDER_FAILED, true, false);
        retry(JwtTestTokens.admin(), failed.getId()).andExpect(status().isOk());
        as(JwtTestTokens.operator(), "/api/v1/ops/audit?entityType=Notification&entityId=" + failed.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("RETRY_REQUESTED"))
                .andExpect(jsonPath("$.content[0].actor").value("admin-test"));
    }

    @Test
    void opsEndpointsAreStaffOnlyAndDescribeThisService() throws Exception {
        problem(as(JwtTestTokens.customer(UUID.randomUUID()), "/api/v1/ops/messaging"), 403, "FORBIDDEN");
        problem(as(JwtTestTokens.customer(UUID.randomUUID()), "/api/v1/ops/audit"), 403, "FORBIDDEN");
        as(JwtTestTokens.operator(), "/api/v1/ops/messaging")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("notification-service"))
                .andExpect(jsonPath("$.consumerGroup").value("notification-service"))
                .andExpect(jsonPath("$.topics[*].topic").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "oms.order.events.v1", "oms.fulfillment.events.v1")))
                .andExpect(jsonPath("$.dltTopics.length()").value(2));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/notifications']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/notifications/{id}/retry']").exists());
    }
}
