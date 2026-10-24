package com.enterprise.oms.aiexamples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Example 02: {@code @Transactional} self-invocation.
 *
 * <p>Spring applies {@code @Transactional} through a proxy around the bean. A call from one method of a bean to
 * another method of the SAME bean ({@code this.x()}) never goes through the proxy, so the annotation is ignored.
 * This file reproduces the mechanism with a JDK dynamic proxy and a custom {@link Tx} annotation, so no Spring is
 * needed. It is a faithful model of the proxy rule, not a Spring integration test.
 */
public final class Ex02SelfInvocation {

    private Ex02SelfInvocation() { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Tx { }

    /** Records what the "transaction manager" did. */
    public static final class TxManager {
        private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
        public final List<String> log = new ArrayList<>();

        public static boolean active() { return ACTIVE.get(); }

        <T> T run(java.util.concurrent.Callable<T> body, String name) throws Exception {
            ACTIVE.set(true);
            log.add("BEGIN " + name);
            try {
                T result = body.call();
                log.add("COMMIT " + name);
                return result;
            } catch (Exception e) {
                log.add("ROLLBACK " + name);
                throw e;
            } finally {
                ACTIVE.set(false);
            }
        }
    }

    /** Wraps a bean in a proxy that opens a transaction around {@link Tx} methods only. */
    @SuppressWarnings("unchecked")
    public static <T> T transactionalProxy(T target, Class<T> iface, TxManager tm) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            Method impl = target.getClass().getMethod(method.getName(), method.getParameterTypes());
            Callable body = () -> {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ex ? ex : e;
                }
            };
            return impl.isAnnotationPresent(Tx.class) ? tm.run(body::call, method.getName()) : body.call();
        });
    }

    private interface Callable extends java.util.concurrent.Callable<Object> { }

    // ------------------------------------------------------------------ flawed

    public interface OrderCancellation {
        void cancelAll(List<String> orderIds);
        void cancelOne(String orderId);
    }

    /** Flawed: cancelAll() calls this.cancelOne(); the @Tx on cancelOne is silently skipped. */
    public static class FlawedCancellation implements OrderCancellation {
        public final List<String> wroteInsideTx = new ArrayList<>();

        @Override
        public void cancelAll(List<String> orderIds) {
            for (String id : orderIds) {
                this.cancelOne(id);               // self-invocation: bypasses the proxy
            }
        }

        @Override
        @Tx
        public void cancelOne(String orderId) {
            wroteInsideTx.add(orderId + ":tx=" + TxManager.active());
        }
    }

    // ------------------------------------------------------------------ fixed

    /** Fixed: the transactional unit lives in a separate bean, injected (so it is called through its proxy). */
    public static class OneOrderCanceller implements OrderCancellation {
        public final List<String> wroteInsideTx = new ArrayList<>();

        @Override public void cancelAll(List<String> orderIds) { throw new UnsupportedOperationException(); }

        @Override
        @Tx
        public void cancelOne(String orderId) {
            wroteInsideTx.add(orderId + ":tx=" + TxManager.active());
            if (orderId.startsWith("BAD")) {
                throw new IllegalStateException("cannot cancel " + orderId);
            }
        }
    }

    public static class FixedCancellation implements OrderCancellation {
        private final OrderCancellation oneOrderProxy;      // the PROXIED collaborator

        public FixedCancellation(OrderCancellation oneOrderProxy) { this.oneOrderProxy = oneOrderProxy; }

        @Override
        public void cancelAll(List<String> orderIds) {
            for (String id : orderIds) {
                try {
                    oneOrderProxy.cancelOne(id);          // goes through the proxy: one tx per order
                } catch (IllegalStateException e) {
                    // one bad order rolls back only its own tx; the rest proceed
                }
            }
        }

        @Override public void cancelOne(String orderId) { oneOrderProxy.cancelOne(orderId); }
    }
}
