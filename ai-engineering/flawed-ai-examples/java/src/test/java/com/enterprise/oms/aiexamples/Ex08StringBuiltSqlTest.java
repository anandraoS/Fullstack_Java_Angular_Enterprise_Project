package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex08StringBuiltSql.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ex08StringBuiltSqlTest {

    private static final String INJECTION = "x' OR '1'='1";
    private static final String SORT_INJECTION = "created_at; DROP TABLE orders";

    @Test
    void flawedLetsUserTextBecomeSqlSyntax() {
        String sql = new FlawedOrderSearch().build(INJECTION, SORT_INJECTION);
        assertTrue(sql.contains("OR '1'='1"), "BUG MANIFESTS: predicate escaped its string literal: " + sql);
        assertTrue(sql.contains("; DROP TABLE orders"), "BUG MANIFESTS: second statement smuggled via ORDER BY");
    }

    @Test
    void fixedKeepsUserTextOutOfTheSqlString() {
        BoundQuery q = new FixedOrderSearch().build(INJECTION, "createdAt", false);

        assertFalse(q.sql().contains("OR '1'='1"));
        assertFalse(q.sql().contains(INJECTION));
        assertTrue(q.sql().contains("LIKE ?"));
        assertEquals("%" + INJECTION + "%", q.params().get(0), "payload is data, bound as a parameter");
        assertTrue(q.sql().endsWith("ORDER BY created_at DESC"));
    }

    @Test
    void fixedRejectsNonWhitelistedSortField() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedOrderSearch().build("acme", SORT_INJECTION, true));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedOrderSearch().build("acme", "customer_email", true));
    }

    @Test
    void fixedEscapesLikeWildcardsSoPercentIsLiteral() {
        BoundQuery q = new FixedOrderSearch().build("100%_off", "status", true);
        assertEquals("%100\\%\\_off%", q.params().get(0));
    }
}
