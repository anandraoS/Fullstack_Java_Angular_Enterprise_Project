# 07 Unbounded pagination and N+1

Code: `java/.../Ex07PaginationNPlusOne.java`
Test: `Ex07PaginationNPlusOneTest` (4 tests)

## Prompt that produces it
"List orders with their items" and "add paging" (answered with `size` taken straight from the query string).

## Flawed code
```java
@GetMapping("/orders")
List<OrderView> list() {
    return orderRepo.findAll().stream()
        .map(o -> new OrderView(o.getId(), itemRepo.findByOrderId(o.getId())))   // 1 query per order
        .toList();
}
// or: PageRequest.of(page, size) with size=1_000_000 from the client
```
`@OneToMany(fetch = EAGER)` or lazy loading inside a serializer produces the same N+1 invisibly.

## Why it is risky here
One request with 50,000 orders loads the table into the heap and issues 50,001 queries: Hikari connections saturate
(`hikaricp.connections.pending` climbs), p99 latency spikes for everyone, and the pod can OOM. It is also a cheap DoS
(API4 unrestricted resource consumption). The runbook `docs/runbooks/04-database-latency-spikes.md` covers the symptom.

## Corrected code
* Validate: `page >= 0`, `1 <= size <= 100`, sort field whitelisted; violation is 400 `VALIDATION_FAILED` and is NOT
  silently clamped (contract section 12). Real class: `com.enterprise.oms.common.web.PageRequestFactory`.
* Fetch children in one query: `WHERE order_id IN (:ids)` (or `@EntityGraph`/`JOIN FETCH` for to-one, batch fetch for
  collections), then group in memory.
* Customers: force the `customerId` filter server-side (see example 05).

## Regression test
Flawed: 2,000 orders return 2,000 views and 2,001 queries. Fixed: page of 20 uses exactly 2 queries and loads 20 + 60
rows; size 101, size 0 and page -1 are rejected; query count is identical for page sizes 5 and 100.

Real-component test: `PageRequestFactoryTest` in `services/platform-common/src/test/java/com/enterprise/oms/common/web/`.
Service-level (exists): `OrderRepositoryTest#searchingAPageOfOrdersWithItemsTakesAConstantNumberOfStatements` (order-service) uses Hibernate
statistics (`generate_statistics` in `application-test.yml`) and asserts `<= 3` statements for a page of 20 orders x 3 items.

## Review prompt to catch it
"What is the maximum number of rows and SQL statements one call to this endpoint can cause?"
