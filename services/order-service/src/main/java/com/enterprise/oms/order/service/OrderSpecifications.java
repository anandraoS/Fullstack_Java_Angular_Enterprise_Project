package com.enterprise.oms.order.service;

import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.CustomerOrder;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Composable filters for the order search (JPA Criteria via Spring Data Specifications; no string-built SQL). */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<CustomerOrder> customer(UUID customerId) {
        return customerId == null ? null : (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<CustomerOrder> status(OrderStatus status) {
        return status == null ? null : (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    /** createdAt >= from (inclusive). */
    public static Specification<CustomerOrder> createdFrom(Instant from) {
        return from == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    /** createdAt <= to (inclusive). */
    public static Specification<CustomerOrder> createdTo(Instant to) {
        return to == null ? null : (root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    /**
     * Free text: a UUID matches the order id exactly; any text is a case-insensitive PREFIX match on the customer
     * email snapshot and the shipping city. (A prefix match, not contains: it stays index-friendly with a
     * functional/trigram index in production; see README.)
     */
    public static Specification<CustomerOrder> text(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String term = q.trim();
        UUID asId = tryUuid(term);
        String like = escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> {
            Predicate byText = cb.or(
                    cb.like(cb.lower(root.get("customerEmail")), like, '\\'),
                    cb.like(cb.lower(root.get("shippingAddress").get("city")), like, '\\'));
            return asId == null ? byText : cb.or(cb.equal(root.get("id"), asId), byText);
        };
    }

    /** Operator "needs attention" view: REJECTED, FAILED, or COMPENSATION_PENDING that has not moved since cutoff. */
    public static Specification<CustomerOrder> failedOrStuck(Instant stuckCutoff) {
        return (root, q, cb) -> cb.or(
                root.get("status").in(OrderStatus.REJECTED, OrderStatus.FAILED),
                cb.and(cb.equal(root.get("status"), OrderStatus.COMPENSATION_PENDING),
                        cb.lessThan(root.get("updatedAt"), stuckCutoff)));
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static UUID tryUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
