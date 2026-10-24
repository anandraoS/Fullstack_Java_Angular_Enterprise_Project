package com.enterprise.oms.aiexamples;

import java.util.List;
import java.util.Map;

/**
 * Example 08: SQL built by string concatenation (OWASP A03 / API8). No database is needed to show the flaw: the test
 * inspects the SQL text the code would send. The real services use Spring Data / named parameters and a sort-field
 * whitelist (contract section 12: non-whitelisted {@code sort} is a 400).
 */
public final class Ex08StringBuiltSql {

    private Ex08StringBuiltSql() { }

    public record BoundQuery(String sql, List<Object> params) { }

    /** Flawed: user text is spliced into both the predicate and the ORDER BY. */
    public static final class FlawedOrderSearch {
        public String build(String customerName, String sort) {
            return "SELECT id, status FROM orders WHERE customer_name LIKE '%" + customerName + "%' ORDER BY " + sort;
        }
    }

    /** Fixed: values are bound parameters; identifiers come only from a whitelist. */
    public static final class FixedOrderSearch {
        private static final Map<String, String> SORT_COLUMNS = Map.of(
                "createdAt", "created_at", "status", "status", "totalAmount", "total_amount");

        public BoundQuery build(String customerName, String sortField, boolean ascending) {
            String column = SORT_COLUMNS.get(sortField);
            if (column == null) {
                throw new IllegalArgumentException("sort field not allowed");
            }
            String sql = "SELECT id, status FROM orders WHERE customer_name LIKE ? ESCAPE '\\' ORDER BY "
                    + column + (ascending ? " ASC" : " DESC");
            return new BoundQuery(sql, List.of("%" + escapeLike(customerName) + "%"));
        }

        static String escapeLike(String s) {
            return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }
    }
}
