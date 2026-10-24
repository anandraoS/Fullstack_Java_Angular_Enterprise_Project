# 02 - BOLA: customerId taken from the request body

Severity: **Critical** (any authenticated customer can act on another customer's account)

## PR title: "Add POST /api/v1/orders and GET /api/v1/orders/{id}"

```diff
 // order-service OrderController (placeholder)
+@PostMapping("/orders")
+@PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
+public ResponseEntity<OrderDto> create(@Valid @RequestBody CreateOrderRequest req,
+                                       @RequestHeader("Idempotency-Key") String key) {
+    Order order = orderService.create(req.customerId(), req.items(), req.shippingAddress(), key);
+    return ResponseEntity.accepted().location(uri(order)).body(OrderDto.from(order));
+}
+
+@GetMapping("/orders/{id}")
+@PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
+public OrderDto get(@PathVariable UUID id) {
+    return orderRepository.findById(id).map(OrderDto::from)
+            .orElseThrow(() -> new NotFoundException("Order", id));
+}
```

## Reasoning

Two separate defects, both Broken Object Level Authorization.

1. **Create**: `req.customerId()` comes from the JSON body. Customer 1 (`c0000000-...-0001`) posts a body with `customerId=c0000000-...-0002`. `@PreAuthorize` passes (role is CUSTOMER). The order is created under Globex's account; their notifications receive the "order received" mail, and credit/limits attributed to them would be consumed.
2. **Read**: `GET /orders/{id}` checks the role but not the owner. UUIDs are not secrets (they appear in emails, logs, URLs); relying on their unguessability is security by obscurity.

Method-level role checks answer "which kind of caller"; they cannot answer "which object". The contract requires: CUSTOMER creates only for own `customerId`, reads only own orders, and another customer's order yields **404** (not 403) so existence is not revealed.

The same pattern must be checked in: `GET /orders/{id}/history`, `POST /orders/{id}/cancel`, `GET /orders?customerId=` (forced filter), `GET /customers/{id}`, `GET /notifications?customerId=`.

## Fix

```java
@PostMapping("/orders")
public ResponseEntity<OrderDto> create(@Valid @RequestBody CreateOrderRequest req,
                                       @RequestHeader("Idempotency-Key") String key) {
    CurrentUser user = CurrentUser.require();                         // platform-common (verified)
    if (!user.canAccessCustomer(req.customerId())) {                  // staff: any; customer: self only
        throw new ForbiddenException("Not allowed to create orders for this customer");
    }
    ...
}

@GetMapping("/orders/{id}")
public OrderDto get(@PathVariable UUID id) {
    CurrentUser user = CurrentUser.require();
    Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order", id));
    if (!user.canAccessCustomer(o.customerId())) {
        throw new NotFoundException("Order", id);                     // identical to the missing case
    }
    return OrderDto.from(o);
}
```

Prefer a single `OrderAccess.loadVisible(id, user)` helper used by get, history and cancel, so there is one place to get it right (and one place to test).

Error bodies for missing and foreign orders must be byte-identical apart from `correlationId`/`timestamp`: same `title`, `detail`, `code=NOT_FOUND`.

## Regression tests

```java
// sketch (MockMvc); JwtTestTokens from platform-test-support generates tokens from a runtime secret
static final UUID CUSTOMER_1 = UUID.fromString("c0000000-0000-0000-0000-000000000001");
static final UUID CUSTOMER_2 = UUID.fromString("c0000000-0000-0000-0000-000000000002");

@Test
void customerCannotCreateOrderForAnotherCustomer() throws Exception {
    mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "Idem-Key-0002")
            .with(bearer(customerToken(CUSTOMER_1))).contentType(APPLICATION_JSON)
            .content(orderJsonFor(CUSTOMER_2)))
       .andExpect(status().isForbidden())
       .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(orderRepository.countByCustomerId(CUSTOMER_2)).isZero();
}

@Test
void customerGets404ForAnotherCustomersOrder_identicalToMissing() throws Exception {
    UUID foreign = persistOrderFor(CUSTOMER_2);
    String foreignBody = mvc.perform(get("/api/v1/orders/{id}", foreign).with(bearer(customerToken(CUSTOMER_1))))
        .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
    String missingBody = mvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()).with(bearer(customerToken(CUSTOMER_1))))
        .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
    // normalise ids and volatile fields, then the two bodies must be identical
    assertThat(normalise(foreignBody)).isEqualTo(normalise(missingBody));
}

@Test
void operatorCanReadAnyOrder() throws Exception {
    mvc.perform(get("/api/v1/orders/{id}", persistOrderFor(CUSTOMER_2)).with(bearer(operatorToken())))
       .andExpect(status().isOk());
}
```

(`normalise` is a small helper in the test class: remove `correlationId` and `timestamp`, and replace every UUID, including the one in `instance` and `detail`, with `ID`.)

## Reviewer checklist from this scenario

- [ ] Is the acting customer derived from the token?
- [ ] Does every `{id}` endpoint compare the owner with the caller?
- [ ] Is "not yours" indistinguishable from "does not exist" where the contract says 404?
- [ ] Is there a test with two customers and one operator?
