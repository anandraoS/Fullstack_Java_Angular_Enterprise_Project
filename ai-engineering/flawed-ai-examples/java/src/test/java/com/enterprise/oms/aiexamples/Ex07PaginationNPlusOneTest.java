package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex07PaginationNPlusOne.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Ex07PaginationNPlusOneTest {

    @Test
    void flawedLoadsEverythingAndIssuesOneQueryPerOrder() {
        Repo repo = new Repo(2_000, 3);
        List<OrderView> result = new FlawedOrderQuery(repo).list();

        assertEquals(2_000, result.size(), "BUG MANIFESTS: whole table returned for one request");
        assertEquals(1 + 2_000, repo.queries, "BUG MANIFESTS: N+1 queries");
    }

    @Test
    void fixedUsesTwoQueriesAndBoundedRows() {
        Repo repo = new Repo(2_000, 3);
        List<OrderView> result = new FixedOrderQuery(repo).list(0, 20);

        assertEquals(20, result.size());
        assertEquals(2, repo.queries, "one page query + one batched child query, independent of page size");
        assertEquals(20 + 20 * 3, repo.rowsLoaded);
        assertEquals(3, result.get(0).skus().size());
    }

    @Test
    void fixedRejectsUnboundedOrInvalidPaging() {
        FixedOrderQuery q = new FixedOrderQuery(new Repo(10, 1));
        assertThrows(IllegalArgumentException.class, () -> q.list(0, 101));
        assertThrows(IllegalArgumentException.class, () -> q.list(0, 0));
        assertThrows(IllegalArgumentException.class, () -> q.list(-1, 20));
        assertDoesNotThrow(() -> q.list(0, 100));
    }

    @Test
    void queryCountDoesNotGrowWithPageSize() {
        Repo small = new Repo(500, 2);
        Repo large = new Repo(500, 2);
        new FixedOrderQuery(small).list(0, 5);
        new FixedOrderQuery(large).list(0, 100);
        assertEquals(small.queries, large.queries);
    }
}
