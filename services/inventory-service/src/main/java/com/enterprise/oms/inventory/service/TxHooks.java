package com.enterprise.oms.inventory.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs side effects that must only happen if the surrounding transaction really committed. */
public final class TxHooks {

    private TxHooks() {
    }

    /** After commit if a transaction is active, immediately otherwise. Exceptions in {@code action} are swallowed (best-effort). */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe(action);
                }
            });
        } else {
            safe(action);
        }
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // metrics / cache eviction are best-effort and must never fail the business transaction
        }
    }
}
