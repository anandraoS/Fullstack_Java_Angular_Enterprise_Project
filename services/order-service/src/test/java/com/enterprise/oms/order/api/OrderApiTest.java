package com.enterprise.oms.order.api;

import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST contract of /api/v1/orders: creation, validation errors, error shape, pagination, history, cancel. */
class OrderApiTest extends AbstractOrderSpringTest {

    private static final AtomicInteger SKU = new AtomicInteger();

    private Customer alice;
    private StubInventoryClient.Product widget;

    @BeforeEach
    void fixtures() {
        alice = newCustomer();
        widget = inventory.addProduct("SKU-T-" + SKU.incrementAndGet(), "12.50", 100);
    }

    @Nested
    @DisplayName("POST /orders")
    class Create {

        @Test
        void returns202WithLocationAndOrderInStatusCreated() throws Exception {
            mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), newKey(), orderJson(alice.getId(), widget.id(), 3)))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", matchesPattern("/api/v1/orders/[0-9a-f-]{36}")))
                    .andExpect(header().doesNotExist("Idempotent-Replay"))
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.status", is("CREATED")))
                    .andExpect(jsonPath("$.customerId", is(alice.getId().toString())))
                    .andExpect(jsonPath("$.currency", is("USD")))
                    .andExpect(jsonPath("$.totalAmount", is(0.0)))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].productId", is(widget.id().toString())))
                    .andExpect(jsonPath("$.items[0].quantity", is(3)))
                    .andExpect(jsonPath("$.items[0].sku").value((Object) null))
                    .andExpect(jsonPath("$.shippingAddress.city", is("Berlin")))
                    .andExpect(jsonPath("$.correlationId", notNullValue()))
                    .andExpect(jsonPath("$.createdAt", notNullValue()))
                    .andExpect(jsonPath("$.version", notNullValue()));
        }

        @Test
        void writesHistoryAuditAndSagaButNoEventsBeforeValidation() throws Exception {
            UUID id = placeOrder(alice, widget, 2);

            mvc.perform(get("/api/v1/orders/{id}/history", id).header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].fromStatus").value((Object) null))
                    .andExpect(jsonPath("$[0].toStatus", is("CREATED")))
                    .andExpect(jsonPath("$[0].actor", is("customer-" + alice.getId())));
            assertThat(auditActions(id)).contains("ORDER_CREATED");
            assertThat(sagas.findByOrderId(id)).isPresent();
            assertThat(outboxTypes(id)).isEmpty();   // OrderCreated carries the price snapshot: sent after validation
        }

        @Test
        void staffCanOrderOnBehalfOfACustomer() throws Exception {
            mvc.perform(createOrder(JwtTestTokens.operator(), newKey(), orderJson(alice.getId(), widget.id(), 1)))
                    .andExpect(status().isAccepted());
            mvc.perform(createOrder(JwtTestTokens.admin(), newKey(), orderJson(alice.getId(), widget.id(), 1)))
                    .andExpect(status().isAccepted());
        }

        @Test
        void customerCannotOrderForSomeoneElse() throws Exception {
            Customer bob = newCustomer();
            mvc.perform(createOrder(JwtTestTokens.customer(bob.getId()), newKey(), orderJson(alice.getId(), widget.id(), 1)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("FORBIDDEN")));
        }

        @Test
        void customerTokenWithoutCustomerIdClaimIsForbidden() throws Exception {
            String token = JwtTestTokens.bearer("customer-without-claim", List.of("CUSTOMER"), null);
            mvc.perform(createOrder(token, newKey(), orderJson(alice.getId(), widget.id(), 1)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void unknownCustomerIs404ForStaff() throws Exception {
            mvc.perform(createOrder(JwtTestTokens.operator(), newKey(), orderJson(UUID.randomUUID(), widget.id(), 1)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code", is("NOT_FOUND")));
        }

        @Test
        void missingIdempotencyKeyIs400WithItsOwnCode() throws Exception {
            mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), null, orderJson(alice.getId(), widget.id(), 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REQUIRED")));
        }

        @Test
        void malformedIdempotencyKeyIsAValidationError() throws Exception {
            for (String bad : List.of("short", "has spaces in it!!", "x".repeat(65), "bad/key/with/slashes")) {
                mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), bad, orderJson(alice.getId(), widget.id(), 1)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
            }
        }
    }

    @Nested
    @DisplayName("request validation and error shape")
    class Validation {

        private String expectInvalid(String body) throws Exception {
            MvcResult r = mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), newKey(), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED"))).andReturn();
            return r.getResponse().getContentAsString();
        }

        private String line(String productId, int q) {
            return "{\"productId\":\"" + productId + "\",\"quantity\":" + q + "}";
        }

        private String body(String items) {
            return "{\"customerId\":\"" + alice.getId() + "\",\"items\":" + items
                    + ",\"shippingAddress\":{\"line1\":\"a\",\"city\":\"c\",\"postalCode\":\"1\",\"country\":\"DE\"}}";
        }

        @Test
        void problemJsonHasEveryContractField() throws Exception {
            mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), newKey(), body("[]")))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                    .andExpect(jsonPath("$.type", is("https://oms.example/problems/validation-failed")))
                    .andExpect(jsonPath("$.title", notNullValue()))
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.detail", notNullValue()))
                    .andExpect(jsonPath("$.instance", is("/api/v1/orders")))
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.correlationId", notNullValue()))
                    .andExpect(jsonPath("$.timestamp", notNullValue()))
                    .andExpect(jsonPath("$.errors[0].field", is("items")));
        }

        @Test
        void emptyItemsQuantityBoundsAndTooManyLines() throws Exception {
            expectInvalid(body("[]"));
            expectInvalid(body("[" + line(widget.id().toString(), 0) + "]"));
            expectInvalid(body("[" + line(widget.id().toString(), 1001) + "]"));
            List<String> fifty1 = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                fifty1.add(line(UUID.randomUUID().toString(), 1));
            }
            expectInvalid(body("[" + String.join(",", fifty1) + "]"));
        }

        @Test
        void quantityBoundariesAreAccepted() throws Exception {
            for (int q : new int[]{1, 1000}) {
                mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), newKey(),
                        orderJson(alice.getId(), widget.id(), q))).andExpect(status().isAccepted());
            }
        }

        @Test
        void fiftyLinesAreAccepted() throws Exception {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                lines.add(line(UUID.randomUUID().toString(), 1));
            }
            mvc.perform(createOrder(JwtTestTokens.customer(alice.getId()), newKey(), body("[" + String.join(",", lines) + "]")))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.items", hasSize(50)));
        }

        @Test
        void duplicateProductIdIsRejected() throws Exception {
            String two = "[" + line(widget.id().toString(), 1) + "," + line(widget.id().toString(), 2) + "]";
            String response = expectInvalid(body(two));
            assertThat(response).contains("duplicate productId");
        }

        @Test
        void missingAndBlankFieldsAreReportedPerField() throws Exception {
            String response = expectInvalid("{\"customerId\":null,\"items\":null,\"shippingAddress\":{\"line1\":\"\",\"city\":\"\"}}");
            JsonNode errors = mapper.readTree(response).get("errors");
            List<String> fields = new ArrayList<>();
            errors.forEach(e -> fields.add(e.get("field").asText()));
            assertThat(fields).contains("customerId", "items", "shippingAddress.line1", "shippingAddress.city",
                    "shippingAddress.postalCode", "shippingAddress.country");
        }

        @Test
        void malformedJsonAndBadUuidAreValidationErrors() throws Exception {
            expectInvalid("{not json");
            expectInvalid("{\"customerId\":\"not-a-uuid\",\"items\":[],\"shippingAddress\":{}}");
        }

        @Test
        void wrongContentTypeIs415AndNeverLeaksInternals() throws Exception {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.TEXT_PLAIN).content("hello")
                            .header("Authorization", JwtTestTokens.customer(alice.getId()))
                            .header("Idempotency-Key", newKey()))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code", is("UNSUPPORTED_MEDIA_TYPE")));
        }
    }

    @Nested
    @DisplayName("GET /orders/{id} and history")
    class Read {

        @Test
        void ownerStaffSeeTheOrderOthersGet404() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            Customer mallory = newCustomer();

            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id", is(id.toString())));
            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.operator()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.customer(mallory.getId())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("NOT_FOUND")));
            mvc.perform(get("/api/v1/orders/{id}/history", id).header("Authorization", JwtTestTokens.customer(mallory.getId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void unknownIdIs404AndMalformedIdIs400() throws Exception {
            mvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/orders/not-a-uuid").header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unknownAndForeignOrdersAreIndistinguishable() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            Customer mallory = newCustomer();
            String foreign = mvc.perform(get("/api/v1/orders/{id}", id)
                    .header("Authorization", JwtTestTokens.customer(mallory.getId()))).andReturn().getResponse().getContentAsString();
            String unknown = mvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID())
                    .header("Authorization", JwtTestTokens.customer(mallory.getId()))).andReturn().getResponse().getContentAsString();
            // same status, same code, same title: no oracle for enumeration
            assertThat(mapper.readTree(foreign).get("code")).isEqualTo(mapper.readTree(unknown).get("code"));
            assertThat(mapper.readTree(foreign).get("title")).isEqualTo(mapper.readTree(unknown).get("title"));
        }

        @Test
        void historyListsTransitionsInOrder() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            validate(id);

            mvc.perform(get("/api/v1/orders/{id}/history", id).header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].toStatus", is("CREATED")))
                    .andExpect(jsonPath("$[1].fromStatus", is("CREATED")))
                    .andExpect(jsonPath("$[1].toStatus", is("VALIDATED")))
                    .andExpect(jsonPath("$[1].actor", is("saga")))
                    .andExpect(jsonPath("$[1].occurredAt", notNullValue()));
        }

        @Test
        void validatedOrderShowsThePriceSnapshotWithScaleTwo() throws Exception {
            UUID id = placeOrder(alice, widget, 3);
            validate(id);

            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(jsonPath("$.status", is("VALIDATED")))
                    .andExpect(jsonPath("$.totalAmount", is(37.5)))
                    .andExpect(jsonPath("$.items[0].sku", is(widget.sku())))
                    .andExpect(jsonPath("$.items[0].unitPrice", is(12.5)))
                    .andExpect(jsonPath("$.items[0].lineTotal", is(37.5)));
            String raw = mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", JwtTestTokens.admin()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(raw).contains("\"totalAmount\":37.50").contains("\"unitPrice\":12.50");
        }
    }

    @Nested
    @DisplayName("GET /orders (search and pagination)")
    class Search {

        private Customer bob;

        @BeforeEach
        void orders() throws Exception {
            bob = newCustomer();
            for (int i = 0; i < 5; i++) {
                placeOrder(alice, widget, i + 1);
            }
            placeOrder(bob, widget, 1);
            placeOrder(bob, widget, 2);
        }

        @Test
        void customerSeesOnlyOwnOrdersWithPagingMetadata() throws Exception {
            mvc.perform(get("/api/v1/orders").param("size", "2").param("page", "1")
                            .header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.page", is(1)))
                    .andExpect(jsonPath("$.size", is(2)))
                    .andExpect(jsonPath("$.totalElements", is(5)))
                    .andExpect(jsonPath("$.totalPages", is(3)))
                    .andExpect(jsonPath("$.content[0].customerId", is(alice.getId().toString())))
                    .andExpect(jsonPath("$.content[0].items", hasSize(1)));
        }

        @Test
        void lastPageHoldsTheRemainder() throws Exception {
            mvc.perform(get("/api/v1/orders").param("size", "2").param("page", "2")
                            .header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(jsonPath("$.content", hasSize(1)));
            mvc.perform(get("/api/v1/orders").param("size", "2").param("page", "9")
                            .header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
        }

        @Test
        void customerIdFilterIsForcedForCustomersWhateverTheyPass() throws Exception {
            mvc.perform(get("/api/v1/orders").param("customerId", bob.getId().toString())
                            .header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements", is(5)))
                    .andExpect(jsonPath("$.content[*].customerId", org.hamcrest.Matchers.everyItem(is(alice.getId().toString()))));
        }

        @Test
        void staffFilterByCustomerStatusAndText() throws Exception {
            mvc.perform(get("/api/v1/orders").param("customerId", bob.getId().toString())
                            .header("Authorization", JwtTestTokens.operator()))
                    .andExpect(jsonPath("$.totalElements", is(2)));
            mvc.perform(get("/api/v1/orders").param("customerId", bob.getId().toString()).param("status", "VALIDATED")
                            .header("Authorization", JwtTestTokens.operator()))
                    .andExpect(jsonPath("$.totalElements", is(0)));
            mvc.perform(get("/api/v1/orders").param("customerId", bob.getId().toString()).param("status", "CREATED")
                            .param("q", "BERL").header("Authorization", JwtTestTokens.admin()))
                    .andExpect(jsonPath("$.totalElements", is(2)));
            mvc.perform(get("/api/v1/orders").param("customerId", bob.getId().toString()).param("q", "paris")
                            .header("Authorization", JwtTestTokens.admin()))
                    .andExpect(jsonPath("$.totalElements", is(0)));
            mvc.perform(get("/api/v1/orders").param("q", bob.getEmail().substring(0, 6))
                            .header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isOk());
        }

        @Test
        void textSearchByOrderIdAndLikeWildcardsAreLiteral() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            mvc.perform(get("/api/v1/orders").param("q", id.toString()).header("Authorization", JwtTestTokens.operator()))
                    .andExpect(jsonPath("$.totalElements", is(1)))
                    .andExpect(jsonPath("$.content[0].id", is(id.toString())));
            mvc.perform(get("/api/v1/orders").param("q", "%").header("Authorization", JwtTestTokens.operator()))
                    .andExpect(jsonPath("$.totalElements", is(0)));
        }

        @Test
        void dateRangeFilters() throws Exception {
            String future = Instant.now().plusSeconds(3600).toString();
            String past = Instant.now().minusSeconds(3600).toString();
            mvc.perform(get("/api/v1/orders").param("from", future).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(jsonPath("$.totalElements", is(0)));
            mvc.perform(get("/api/v1/orders").param("to", past).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(jsonPath("$.totalElements", is(0)));
            mvc.perform(get("/api/v1/orders").param("from", past).param("to", future)
                            .param("customerId", alice.getId().toString()).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(jsonPath("$.totalElements", is(5)));
            mvc.perform(get("/api/v1/orders").param("from", future).param("to", past)
                            .header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        void sortingAscendingAndDescending() throws Exception {
            MvcResult asc = mvc.perform(get("/api/v1/orders").param("sort", "totalAmount,asc").param("sort", "createdAt,asc")
                    .header("Authorization", JwtTestTokens.customer(alice.getId()))).andExpect(status().isOk()).andReturn();
            MvcResult byCreatedDesc = mvc.perform(get("/api/v1/orders").param("sort", "createdAt,desc")
                    .header("Authorization", JwtTestTokens.customer(alice.getId()))).andExpect(status().isOk()).andReturn();
            List<String> created = new ArrayList<>();
            mapper.readTree(byCreatedDesc.getResponse().getContentAsString()).get("content")
                    .forEach(n -> created.add(n.get("createdAt").asText()));
            assertThat(created).isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(mapper.readTree(asc.getResponse().getContentAsString()).get("content")).hasSize(5);
        }

        @Test
        void invalidPagingAndSortParametersAre400NotClamped() throws Exception {
            String t = JwtTestTokens.admin();
            mvc.perform(get("/api/v1/orders").param("size", "0").header("Authorization", t)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/orders").param("size", "101").header("Authorization", t)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/orders").param("page", "-1").header("Authorization", t)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/orders").param("page", "abc").header("Authorization", t)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/orders").param("sort", "password,asc").header("Authorization", t))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
            mvc.perform(get("/api/v1/orders").param("status", "NOPE").header("Authorization", t)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/orders").param("size", "100").header("Authorization", t)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /orders/{id}/cancel")
    class Cancel {

        @Test
        void customerCancelsBeforeValidationWithImmediateCancelledAndEvent() throws Exception {
            UUID id = placeOrder(alice, widget, 1);

            mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"changed my mind\"}")
                            .header("Authorization", JwtTestTokens.customer(alice.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("CANCELLED")))
                    .andExpect(jsonPath("$.statusReason", is("changed my mind")));
            assertThat(outboxTypes(id)).containsExactly("OrderCancelled");
            assertThat(auditActions(id)).contains("ORDER_CANCEL_REQUESTED", "STATUS_CANCELLED");
        }

        @Test
        void cancelWithoutABodyUsesADefaultReason() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.operator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusReason", is("Cancelled by staff")));
        }

        @Test
        void cancellingTwiceIs409InvalidStateTransition() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            String token = JwtTestTokens.customer(alice.getId());
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", token)).andExpect(status().isOk());
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")))
                    .andExpect(jsonPath("$.detail", containsString("CANCELLED")));
        }

        @Test
        void cancellingAFulfilledOrderIs409() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            validate(id);
            deliverInventory(com.enterprise.oms.order.support.TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));
            deliverFulfillment(com.enterprise.oms.order.support.TestEvents.fulfillmentCompleted(id, "TRK-1"));
            assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);

            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        }

        @Test
        void cancellingAnotherCustomersOrderIs404AndChangesNothing() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            Customer mallory = newCustomer();
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.customer(mallory.getId())))
                    .andExpect(status().isNotFound());
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CREATED);
        }

        @Test
        void reasonLongerThan500CharactersIsRejected() throws Exception {
            UUID id = placeOrder(alice, widget, 1);
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"" + "x".repeat(501) + "\"}")
                            .header("Authorization", JwtTestTokens.admin()))
                    .andExpect(status().isBadRequest());
        }
    }
}
