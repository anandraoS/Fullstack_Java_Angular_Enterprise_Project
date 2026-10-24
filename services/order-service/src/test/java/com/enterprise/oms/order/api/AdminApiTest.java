package com.enterprise.oms.order.api;

import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/v1/admin/**: failed-orders worklist, retry, saga inspection. */
class AdminApiTest extends AbstractOrderSpringTest {

    @Autowired SagaProperties props;
    private long originalStuckAfter;

    @BeforeEach
    void remember() {
        originalStuckAfter = props.getStuckAfterSeconds();
    }

    @AfterEach
    void restore() {
        props.setStuckAfterSeconds(originalStuckAfter);
    }

    private List<String> failedIds() throws Exception {
        String body = mvc.perform(get("/api/v1/admin/orders/failed").param("size", "100")
                        .header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        mapper.readTree(body).get("content").forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    @Test
    void failedListHoldsRejectedFailedAndStuckCompensationsOnly() throws Exception {
        UUID rejected = validatedOrder();
        deliverInventory(TestEvents.reservationFailed(rejected, order(rejected).getItems().get(0).getProductId(), 2, 0));
        UUID failed = validatedOrder();
        deliverInventory(TestEvents.released(failed, UUID.randomUUID(), "EXPIRED"));
        UUID stuckCompensation = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentFailed(stuckCompensation, "x"));
        UUID created = createdOrder();
        UUID validated = validatedOrder();
        UUID confirmed = confirmedOrder();
        UUID fulfilled = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentCompleted(fulfilled, "T"));
        UUID cancelled = createdOrder();
        mvc.perform(post("/api/v1/orders/{id}/cancel", cancelled).header("Authorization", JwtTestTokens.admin()));

        props.setStuckAfterSeconds(3600);   // a compensation that started a moment ago is NOT stuck yet
        assertThat(failedIds()).contains(rejected.toString(), failed.toString())
                .doesNotContain(stuckCompensation.toString(), created.toString(), validated.toString(),
                        confirmed.toString(), fulfilled.toString(), cancelled.toString());

        props.setStuckAfterSeconds(0);
        Thread.sleep(20);
        assertThat(failedIds()).contains(rejected.toString(), failed.toString(), stuckCompensation.toString())
                .doesNotContain(created.toString(), validated.toString(), confirmed.toString(), fulfilled.toString(),
                        cancelled.toString());
    }

    @Test
    void failedListIsPagedAndReturnsFullOrderJson() throws Exception {
        UUID rejected = validatedOrder();
        deliverInventory(TestEvents.reservationFailed(rejected, order(rejected).getItems().get(0).getProductId(), 2, 0));

        mvc.perform(get("/api/v1/admin/orders/failed").param("size", "1").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.size", is(1)))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content[0].items[0].productId", notNullValue()))
                .andExpect(jsonPath("$.content[0].statusReason", notNullValue()));
        mvc.perform(get("/api/v1/admin/orders/failed").param("size", "0").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retryResendsTheCurrentStepAndReturnsTheOrder() throws Exception {
        UUID id = validatedOrder();

        mvc.perform(post("/api/v1/admin/orders/{id}/retry", id).header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.status", is("VALIDATED")));

        assertThat(outboxCount(id, "ReserveInventory")).isEqualTo(2);
        assertThat(auditActions(id)).contains("SAGA_REDRIVEN");
    }

    @Test
    void retryOfAnOrderStuckInCreatedRunsValidation() throws Exception {
        UUID id = createdOrder();
        mvc.perform(post("/api/v1/admin/orders/{id}/retry", id).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("VALIDATED")));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.VALIDATED);
    }

    @Test
    void retryOfATerminalOrderIs409AndOfAnUnknownOrderIs404() throws Exception {
        UUID rejected = validatedOrder();
        deliverInventory(TestEvents.reservationFailed(rejected, order(rejected).getItems().get(0).getProductId(), 2, 0));

        mvc.perform(post("/api/v1/admin/orders/{id}/retry", rejected).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        mvc.perform(post("/api/v1/admin/orders/{id}/retry", UUID.randomUUID()).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sagaEndpointShowsInstanceAndStepLog() throws Exception {
        UUID id = confirmedOrder();

        String body = mvc.perform(get("/api/v1/admin/saga/{orderId}", id).header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId", is(id.toString())))
                .andExpect(jsonPath("$.sagaId", notNullValue()))
                .andExpect(jsonPath("$.state", is("RUNNING")))
                .andExpect(jsonPath("$.currentStep", is("FULFILL")))
                .andExpect(jsonPath("$.attempts", is(0)))
                .andExpect(jsonPath("$.startedAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andExpect(jsonPath("$.steps[0].step", is("VALIDATE")))
                .andReturn().getResponse().getContentAsString();
        JsonNode steps = mapper.readTree(body).get("steps");
        List<String> sequence = new ArrayList<>();
        steps.forEach(s -> sequence.add(s.get("step").asText() + ":" + s.get("status").asText()));
        assertThat(sequence).containsSubsequence("VALIDATE:STARTED", "VALIDATE:SUCCEEDED", "RESERVE_INVENTORY:STARTED",
                "RESERVE_INVENTORY:SUCCEEDED", "CONFIRM_ORDER:SUCCEEDED", "FULFILL:STARTED");
        assertThat(steps.get(0).has("attempt")).isTrue();
        assertThat(steps.get(0).has("detail")).isTrue();
        assertThat(steps.get(0).has("at")).isTrue();

        mvc.perform(get("/api/v1/admin/saga/{orderId}", UUID.randomUUID()).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sagaEndpointReportsLastErrorsOfAnOutage() throws Exception {
        UUID id = createdOrder();
        inventory.setUnavailable(true);
        try {
            validate(id);
        } finally {
            inventory.setUnavailable(false);
        }
        mvc.perform(get("/api/v1/admin/saga/{orderId}", id).header("Authorization", JwtTestTokens.admin()))
                .andExpect(jsonPath("$.attempts", is(1)))
                .andExpect(jsonPath("$.lastError").value(org.hamcrest.Matchers.containsString("InventoryUnavailableException")))
                .andExpect(jsonPath("$.steps[?(@.status=='FAILED')]").exists());
    }

    @Test
    void cancelInventoryReleasedRoundTripIsVisibleInHistory() throws Exception {
        UUID id = confirmedOrder();
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"r\"}").header("Authorization", JwtTestTokens.operator())).andExpect(status().isOk());
        deliverInventory(TestEvents.released(id, sagas.findByOrderId(id).orElseThrow().getReservationId(), "CANCELLATION"));

        mvc.perform(get("/api/v1/orders/{id}/history", id).header("Authorization", JwtTestTokens.admin()))
                .andExpect(jsonPath("$[-1:].toStatus", org.hamcrest.Matchers.contains("CANCELLED")))
                .andExpect(jsonPath("$[-2:].toStatus", org.hamcrest.Matchers.contains("COMPENSATION_PENDING", "CANCELLED")))
                .andExpect(jsonPath("$[-2:].actor", org.hamcrest.Matchers.contains("operator-test", "saga")));
    }
}
