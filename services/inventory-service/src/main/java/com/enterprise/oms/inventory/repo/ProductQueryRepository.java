package com.enterprise.oms.inventory.repo;

import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Stock;
import com.enterprise.oms.inventory.service.ProductView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic product + stock search (one query joins {@code products} and {@code stock}; values are always bound
 * parameters, sort fields are mapped from a whitelist - never concatenated into SQL).
 */
@Repository
public class ProductQueryRepository {

    /**
     * @param q            case-insensitive "contains" on name or sku (null = no filter)
     * @param sku          case-insensitive "contains" on sku
     * @param category     exact category
     * @param inStock      true: available &gt; 0, false: available &lt;= 0, null: no filter
     * @param lowStockAt   when set, only products with available &lt;= this value
     * @param activeOnly   hide deactivated products (customers)
     */
    public record Filter(String q, String sku, String category, Boolean inStock, Integer lowStockAt,
                         boolean activeOnly) {
    }

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public Page<ProductView> search(Filter f, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Product> p = cq.from(Product.class);
        Root<Stock> s = cq.from(Stock.class);
        cq.select(cb.tuple(p, s)).where(predicates(cb, p, s, f).toArray(Predicate[]::new));
        List<Order> orders = new ArrayList<>();
        pageable.getSort().forEach(o -> {
            Expression<?> e = sortExpression(cb, p, s, o.getProperty());
            orders.add(o.isAscending() ? cb.asc(e) : cb.desc(e));
        });
        orders.add(cb.asc(p.get("id")));   // stable paging
        cq.orderBy(orders);
        TypedQuery<Tuple> tq = em.createQuery(cq);
        tq.setFirstResult((int) pageable.getOffset());
        tq.setMaxResults(pageable.getPageSize());
        List<ProductView> content = tq.getResultList().stream()
                .map(t -> ProductView.of(t.get(0, Product.class), t.get(1, Stock.class))).toList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<Product> cp = count.from(Product.class);
        Root<Stock> cs = count.from(Stock.class);
        count.select(cb.count(cp)).where(predicates(cb, cp, cs, f).toArray(Predicate[]::new));
        long total = em.createQuery(count).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    private static List<Predicate> predicates(CriteriaBuilder cb, Root<Product> p, Root<Stock> s, Filter f) {
        List<Predicate> preds = new ArrayList<>();
        preds.add(cb.equal(s.get("productId"), p.get("id")));
        if (f.activeOnly()) {
            preds.add(cb.isTrue(p.get("active")));
        }
        if (notBlank(f.q())) {
            String pattern = like(f.q());
            preds.add(cb.or(cb.like(cb.lower(p.get("name")), pattern, '\\'),
                    cb.like(cb.lower(p.get("sku")), pattern, '\\')));
        }
        if (notBlank(f.sku())) {
            preds.add(cb.like(cb.lower(p.get("sku")), like(f.sku()), '\\'));
        }
        if (notBlank(f.category())) {
            preds.add(cb.equal(p.get("category"), f.category().trim()));
        }
        Expression<Integer> available = cb.diff(s.<Integer>get("onHand"), s.<Integer>get("reserved"));
        if (f.inStock() != null) {
            preds.add(f.inStock() ? cb.greaterThan(available, 0) : cb.lessThanOrEqualTo(available, 0));
        }
        if (f.lowStockAt() != null) {
            preds.add(cb.lessThanOrEqualTo(available, f.lowStockAt()));
        }
        return preds;
    }

    private static Expression<?> sortExpression(CriteriaBuilder cb, Root<Product> p, Root<Stock> s, String property) {
        return switch (property) {
            case "available" -> cb.diff(s.<Integer>get("onHand"), s.<Integer>get("reserved"));
            case "onHand", "reserved" -> s.get(property);
            default -> p.get(property);
        };
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** "%term%" with LIKE wildcards in the user input escaped (escape char is a backslash). */
    static String like(String term) {
        String t = term.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + t + "%";
    }
}
