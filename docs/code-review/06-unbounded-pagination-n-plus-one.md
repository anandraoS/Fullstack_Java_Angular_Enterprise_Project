# 06 - Unbounded pagination and N+1 queries

Severity: **Medium** at demo scale, **High** in production (latency, memory, easy denial of service by any customer)

## PR title: "Add order listing for operators and customers"

```diff
+@GetMapping("/orders")
+public List<OrderDto> list(@RequestParam(required = false) UUID customerId,
+                           @RequestParam(required = false, defaultValue = "0") int page,
+                           @RequestParam(required = false, defaultValue = "1000000") int size,
+                           @RequestParam(required = false) String sort) {
+    List<Order> orders = customerId == null ? orderRepository.findAll()
+                                            : orderRepository.findByCustomerId(customerId);
+    return orders.stream()
+        .map(o -> new OrderDto(o, o.getItems(), customerRepository.findById(o.getCustomerId()).orElseThrow()))
+        .toList();
+}
```

## Reasoning

1. **Unbounded**: `size` default 1,000,000 and ignored by the query anyway (`findAll()`); with 200k orders the response and the entity graph live in heap. A customer can call this in a loop: memory pressure and GC pauses (`jvm.gc.pause`), outage for everyone.
2. **N+1**: `o.getItems()` (lazy collection) is one SELECT per order; `customerRepository.findById` is another per order. 1 + 2N statements; at 5,000 rows about 10,000 round trips holding one connection (`hikaricp.connections.active` pegged).
3. **Authorization hole (see 02)**: a CUSTOMER can pass any `customerId`. Contract: for customers the filter is forced to their own id.
4. **Contract violations**: the response must be the pagination envelope `{content,page,size,totalElements,totalPages}`; `size` over 100, `page < 0` or non-whitelisted `sort` must be 400 `VALIDATION_FAILED`, not clamped. `sort` as a free string passed to JPA risks property-name probing and errors that leak internals.
5. **Entity in DTO**: entities with lazy associations leak into serialization (`LazyInitializationException` or open-in-view masking it).

## Fix

```java
@GetMapping("/orders")
public PageResponse<OrderSummaryDto> list(OrderSearchParams p) {
    CurrentUser user = CurrentUser.require();
    UUID forced = user.isCustomer() ? user.customerId() : p.customerId();           // forced filter for customers
    Pageable pageable = pageRequestFactory.create(p.page(), p.size(), p.sort(),     // PageRequestFactory: platform-common (verified)
            Set.of("createdAt", "updatedAt", "status", "totalAmount"),              // whitelist
            Sort.by(Sort.Direction.DESC, "createdAt"));                             // default sort
    Page<OrderSummaryRow> page = orderRepository.search(forced, p.status(), p.from(), p.to(), p.q(), pageable);
    return PageResponse.from(page, OrderSummaryDto::from);                          // PageResponse.from(Page, mapper)
}
```

```java
// repository projection: customer name joined, no entity graph, items not loaded for the list
@Query("""
  select new com.enterprise.oms.order.web.OrderSummaryRow(o.id, o.status, o.totalAmount, o.createdAt, c.name)
    from Order o join Customer c on c.id = o.customerId
   where (:customerId is null or o.customerId = :customerId)
     and (:status is null or o.status = :status)
""")
Page<OrderSummaryRow> search(UUID customerId, OrderStatus status, Instant from, Instant to, String q, Pageable pageable);
```

(The `Customer` entity and the `com.enterprise.oms.order.web` package are placeholders; use the final names.) If line items are needed in the list, load them for the page ids in one `IN` query. Add the supporting index: `orders(customer_id, created_at)`; verify with `EXPLAIN` on PostgreSQL (H2 plans differ).

## Regression tests

```java
// sketch
@Test
void sizeAboveMaximum_is400_notClamped() throws Exception {
    mvc.perform(get("/api/v1/orders?size=101").with(bearer(operatorToken())))
       .andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
}

@Test
void unknownSortField_is400() throws Exception {
    mvc.perform(get("/api/v1/orders?sort=passwordHash,asc").with(bearer(operatorToken())))
       .andExpect(status().isBadRequest());
}

@Test
void customerFilterIsForced() throws Exception {
    persistOrders(CUSTOMER_1, 3); persistOrders(CUSTOMER_2, 4);
    mvc.perform(get("/api/v1/orders?customerId=" + CUSTOMER_2).with(bearer(customerToken(CUSTOMER_1))))
       .andExpect(jsonPath("$.totalElements").value(3));
}

@Test
void pageOf20_runsAtMostThreeStatements() throws Exception {
    persistOrdersWithItems(CUSTOMER_1, 50, 3);
    Statistics stats = sessionFactory.getStatistics(); stats.clear();
    mvc.perform(get("/api/v1/orders?size=20").with(bearer(customerToken(CUSTOMER_1)))).andExpect(status().isOk());
    assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);   // requires hibernate.generate_statistics=true in test profile
}
```

## Reviewer checklist from this scenario

- [ ] Every collection endpoint returns a page and goes through `PageRequestFactory`.
- [ ] Sort fields are whitelisted.
- [ ] A test asserts the statement count for a page.
- [ ] Customers cannot widen the filter.
- [ ] DTOs, not entities, leave the service layer.
