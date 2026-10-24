package com.enterprise.oms.common.web;

import com.enterprise.oms.common.error.FieldError;
import com.enterprise.oms.common.error.RequestValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds a safe {@link Pageable} from page, size and sort (field,asc|desc) query params: page >= 0,
 * 1 <= size <= max (100) else 400 VALIDATION_FAILED, sort fields restricted to a whitelist
 * (never sort on arbitrary client-supplied property names).
 */
public class PageRequestFactory {

    private final int maxSize;
    private final int defaultSize;

    public PageRequestFactory() {
        this(100, 20);
    }

    public PageRequestFactory(int maxSize, int defaultSize) {
        this.maxSize = maxSize;
        this.defaultSize = defaultSize;
    }

    /**
     * @param sorts       raw sort values, each "field" or "field,asc|desc" (may be null/empty)
     * @param sortable    allowed field names (API names; must equal entity property names)
     * @param defaultSort used when sorts is empty
     */
    public Pageable create(Integer page, Integer size, List<String> sorts, Set<String> sortable, Sort defaultSort) {
        List<FieldError> errors = new ArrayList<>();
        int p = page == null ? 0 : page;
        int s = size == null ? defaultSize : size;
        if (p < 0) {
            errors.add(new FieldError("page", "must be >= 0"));
        }
        if (s < 1 || s > maxSize) {
            errors.add(new FieldError("size", "must be between 1 and " + maxSize));
        }
        Sort sort = defaultSort == null ? Sort.unsorted() : defaultSort;
        if (sorts != null && !sorts.isEmpty()) {
            // Tokens may arrive as "status,desc" (one value) or ["status","desc"] (Spring splits a List<String>
            // request param on commas), so parse token by token: a field starts an order, asc/desc sets its direction.
            List<Sort.Order> orders = new ArrayList<>();
            String pendingField = null;
            boolean directionSet = false;
            for (String raw : sorts) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                for (String token : raw.split(",")) {
                    String t = token.trim();
                    String lower = t.toLowerCase(Locale.ROOT);
                    if ((lower.equals("asc") || lower.equals("desc")) && pendingField != null && !directionSet) {
                        orders.set(orders.size() - 1, new Sort.Order(
                                lower.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, pendingField));
                        directionSet = true;
                    } else if (sortable.contains(t)) {
                        orders.add(Sort.Order.asc(t));
                        pendingField = t;
                        directionSet = false;
                    } else {
                        if (errors.stream().noneMatch(e -> e.field().equals("sort"))) errors.add(new FieldError("sort", "unsupported sort field or direction, allowed fields: "
                                + String.join(",", new TreeSet<>(sortable)) + "; direction asc|desc"));
                        pendingField = null;
                    }
                }
            }
            if (!orders.isEmpty()) {
                sort = Sort.by(orders);
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException("Invalid paging parameters", errors);
        }
        return PageRequest.of(p, s, sort);
    }

    /** Single sort value convenience. */
    public Pageable create(Integer page, Integer size, String sort, Set<String> sortable, Sort defaultSort) {
        return create(page, size, sort == null ? null : List.of(sort), sortable, defaultSort);
    }
}
