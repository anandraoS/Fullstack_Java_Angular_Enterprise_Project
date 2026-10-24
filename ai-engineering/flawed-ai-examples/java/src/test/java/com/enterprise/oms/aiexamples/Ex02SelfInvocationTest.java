package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex02SelfInvocation.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Ex02SelfInvocationTest {

    @Test
    void flawedSelfInvocationRunsWithoutTransaction() {
        TxManager tm = new TxManager();
        FlawedCancellation target = new FlawedCancellation();
        OrderCancellation proxy = Ex02SelfInvocation.transactionalProxy(target, OrderCancellation.class, tm);

        proxy.cancelAll(List.of("o-1", "o-2"));      // external callers go through the proxy...

        // ...but cancelAll is not @Tx and calls this.cancelOne, so no transaction was ever opened.
        assertTrue(tm.log.isEmpty(), "BUG MANIFESTS: no BEGIN/COMMIT recorded: " + tm.log);
        assertEquals(List.of("o-1:tx=false", "o-2:tx=false"), target.wroteInsideTx);
    }

    @Test
    void fixedCollaboratorIsCalledThroughProxyAndGetsOneTransactionPerOrder() {
        TxManager tm = new TxManager();
        OneOrderCanceller one = new OneOrderCanceller();
        OrderCancellation oneProxy = Ex02SelfInvocation.transactionalProxy(one, OrderCancellation.class, tm);
        OrderCancellation service = new FixedCancellation(oneProxy);

        service.cancelAll(List.of("o-1", "o-2"));

        assertEquals(List.of("BEGIN cancelOne", "COMMIT cancelOne", "BEGIN cancelOne", "COMMIT cancelOne"), tm.log);
        assertEquals(List.of("o-1:tx=true", "o-2:tx=true"), one.wroteInsideTx);
    }

    @Test
    void fixedFailureRollsBackOnlyTheFailingOrder() {
        TxManager tm = new TxManager();
        OneOrderCanceller one = new OneOrderCanceller();
        OrderCancellation service = new FixedCancellation(
                Ex02SelfInvocation.transactionalProxy(one, OrderCancellation.class, tm));

        service.cancelAll(List.of("o-1", "BAD-2", "o-3"));

        assertEquals(List.of("BEGIN cancelOne", "COMMIT cancelOne",
                "BEGIN cancelOne", "ROLLBACK cancelOne",
                "BEGIN cancelOne", "COMMIT cancelOne"), tm.log);
    }
}
