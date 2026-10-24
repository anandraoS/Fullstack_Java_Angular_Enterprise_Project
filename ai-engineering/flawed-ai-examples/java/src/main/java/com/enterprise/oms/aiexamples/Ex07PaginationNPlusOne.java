package com.enterprise.oms.aiexamples;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Example 07: unbounded list endpoint plus N+1 queries. Contract section 5/12: {@code size} outside 1..100 is a 400
 * (never silently clamped); see {@code com.enterprise.oms.common.web.PageRequestFactory}.
 */
public final class Ex07PaginationNPlusOne {

    private Ex07PaginationNPlusOne() { }

    public record Order(int id) { }
    public record Item(int orderId, String sku) { }
    public record OrderView(int id, List<String> skus) { }

    /** In-memory repository that counts "SQL statements" and "rows loaded". */
    public static final class Repo {
        private final List<Order> orders = new ArrayList<>();
        private final List<Item> items = new ArrayList<>();
        public int queries;
        public int rowsLoaded;

        public Repo(int orderCount, int itemsPerOrder) {
            for (int i = 1; i <= orderCount; i++) {
                orders.add(new Order(i));
                for (int j = 0; j < itemsPerOrder; j++) items.add(new Item(i, "SKU-" + (1000 + j)));
            }
        }

        public List<Order> findAll() { queries++; rowsLoaded += orders.size(); return new ArrayList<>(orders); }

        public List<Order> findPage(int page, int size) {
            queries++;
            List<Order> slice = orders.stream().skip((long) page * size).limit(size).toList();
            rowsLoaded += slice.size();
            return slice;
        }

        public List<Item> findItemsByOrderId(int orderId) {
            queries++;
            List<Item> r = items.stream().filter(i -> i.orderId() == orderId).toList();
            rowsLoaded += r.size();
            return r;
        }

        /** SELECT ... WHERE order_id IN (:ids) */
        public List<Item> findItemsByOrderIds(Collection<Integer> ids) {
            queries++;
            List<Item> r = items.stream().filter(i -> ids.contains(i.orderId())).toList();
            rowsLoaded += r.size();
            return r;
        }
    }

    /** Flawed: findAll(), then one query per order. */
    public static final class FlawedOrderQuery {
        private final Repo repo;
        public FlawedOrderQuery(Repo repo) { this.repo = repo; }

        public List<OrderView> list() {
            List<OrderView> out = new ArrayList<>();
            for (Order o : repo.findAll()) {
                out.add(new OrderView(o.id(), repo.findItemsByOrderId(o.id()).stream().map(Item::sku).toList()));
            }
            return out;
        }
    }

    /** Fixed: validated page window and one batched child query. */
    public static final class FixedOrderQuery {
        public static final int MAX_SIZE = 100;
        private final Repo repo;
        public FixedOrderQuery(Repo repo) { this.repo = repo; }

        public List<OrderView> list(int page, int size) {
            if (page < 0 || size < 1 || size > MAX_SIZE) {
                throw new IllegalArgumentException("page>=0 and 1<=size<=" + MAX_SIZE);
            }
            List<Order> orders = repo.findPage(page, size);
            Map<Integer, List<String>> skusByOrder = new HashMap<>();
            repo.findItemsByOrderIds(orders.stream().map(Order::id).toList())
                    .forEach(i -> skusByOrder.computeIfAbsent(i.orderId(), k -> new ArrayList<>()).add(i.sku()));
            return orders.stream()
                    .map(o -> new OrderView(o.id(), skusByOrder.getOrDefault(o.id(), List.of())))
                    .collect(Collectors.toList());
        }
    }
}
