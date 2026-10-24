# 05 Trusting `customerId` from the request body (BOLA / IDOR)

Code: `java/.../Ex05Bola.java`
Test: `Ex05BolaTest` (4 tests)

## Prompt that produces it
"POST /orders takes customerId and items and creates the order. GET /orders/{id} returns an order."

## Flawed code
```java
@PostMapping("/orders")
Order create(@RequestBody CreateOrderRequest body) {
    return orders.create(body.customerId(), body.items());     // whose customerId? the caller's claim
}
@GetMapping("/orders/{id}")
Order get(@PathVariable UUID id) {
    return orders.findById(id).orElseThrow(NotFoundException::new);   // any authenticated user, any id
}
```

## Why it is risky here
OWASP API1 (broken object-level authorization). With a valid CUSTOMER token for `customer1`, an attacker places orders
as customer2 and reads any order by guessing or harvesting ids. The edge JWT check only proves "some customer";
authorization of the OBJECT must be done in the service (contract section 4). Returning 403 for another customer's
order also leaks existence, so the contract mandates 404.

## Corrected code
```java
Order create(CreateOrderRequest body, CurrentUser user) {
    if (!user.isStaff() && !body.customerId().equals(user.customerId())) throw new ForbiddenException();
    ...
}
Order get(UUID id, CurrentUser user) {
    Order o = store.find(id);
    if (o == null || !(user.isStaff() || o.customerId().equals(user.customerId()))) throw new NotFoundException();
    return o;
}
```
The real helper is `CurrentUser.canAccessCustomer(UUID)` in `services/platform-common`
(`com.enterprise.oms.common.security.CurrentUser`); list endpoints force the `customerId` filter for CUSTOMER role
rather than trusting a query parameter.

## Regression test
Flawed: customer1 creates an order owned by customer2, and customer2 reads customer1's order (both succeed: bug).
Fixed: create for another customer is Forbidden; read of another customer's order and read of a random id both throw the
same `NotFoundException`; owner and operator can read.

Service-level tests (exist in order-service, MockMvc with `JwtTestTokens`): `OrderSecurityTest#customerCannotReachOtherCustomersDataByAnyRoute`,
`#customerCannotChangeIdentityViaBodyOrHeaders`, `OrderApiTest#cancellingAnotherCustomersOrderIs404AndChangesNothing` and the forced own-orders list filter
tests in `OrderApiTest`; live: `scripts/live-flows.mjs` cross-customer checks (404 on get/history/cancel, 403 creating for another customer).
Tracked in `ai-engineering/specs/features/order-cancellation.md` (cancel by non-owner -> 404).

## Review prompt to catch it
"Where does this handler read the caller's identity, and which line proves the object belongs to them?"
