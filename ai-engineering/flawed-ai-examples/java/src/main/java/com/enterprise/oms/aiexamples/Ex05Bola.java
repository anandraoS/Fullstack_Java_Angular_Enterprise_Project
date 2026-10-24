package com.enterprise.oms.aiexamples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Example 05: broken object-level authorization (OWASP API1) from trusting {@code customerId} in the request body
 * and from loading orders by id with no ownership check. The real helper is
 * {@code com.enterprise.oms.common.security.CurrentUser#canAccessCustomer}. Contract section 4: other customers'
 * orders answer 404 (not 403) to prevent enumeration.
 */
public final class Ex05Bola {

    private Ex05Bola() { }

    public record CurrentUser(String id, Set<String> roles, UUID customerId) {
        boolean isStaff() { return roles.contains("OPERATOR") || roles.contains("ADMIN"); }
    }

    public record CreateOrderRequest(UUID customerId, List<String> productIds) { }

    public record Order(UUID id, UUID customerId) { }

    public static class NotFoundException extends RuntimeException { }
    public static class ForbiddenException extends RuntimeException { }

    public static class OrderStore {
        private final Map<UUID, Order> orders = new HashMap<>();
        public Order save(Order o) { orders.put(o.id(), o); return o; }
        public Order find(UUID id) { return orders.get(id); }
    }

    /** Flawed: the caller decides whose order it is, and anyone can read any id. */
    public static class FlawedOrderController {
        private final OrderStore store;
        public FlawedOrderController(OrderStore store) { this.store = store; }

        public Order create(CreateOrderRequest body, CurrentUser user) {
            return store.save(new Order(UUID.randomUUID(), body.customerId()));     // trusts the body
        }

        public Order get(UUID id, CurrentUser user) {
            Order o = store.find(id);
            if (o == null) throw new NotFoundException();
            return o;                                                               // no ownership check
        }
    }

    /** Fixed: identity comes from the verified token; resource-level check on every read. */
    public static class FixedOrderController {
        private final OrderStore store;
        public FixedOrderController(OrderStore store) { this.store = store; }

        public Order create(CreateOrderRequest body, CurrentUser user) {
            if (!user.isStaff() && !body.customerId().equals(user.customerId())) {
                throw new ForbiddenException();
            }
            return store.save(new Order(UUID.randomUUID(), body.customerId()));
        }

        public Order get(UUID id, CurrentUser user) {
            Order o = store.find(id);
            boolean allowed = o != null && (user.isStaff() || o.customerId().equals(user.customerId()));
            if (!allowed) throw new NotFoundException();      // same answer for "missing" and "not yours"
            return o;
        }
    }
}
