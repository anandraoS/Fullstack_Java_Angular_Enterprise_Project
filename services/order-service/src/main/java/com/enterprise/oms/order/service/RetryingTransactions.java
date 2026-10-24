package com.enterprise.oms.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs a unit of work in its own transaction and retries it when optimistic locking (or a DB-level concurrency
 * failure) says another transaction got there first. The work is re-executed from scratch (it must re-read state),
 * which is exactly what makes {@code @Version} safe: the retry sees the winner's state and decides again
 * (e.g. a cancel that lost against a saga event now sees the new status and answers correctly).
 */
@Component
public class RetryingTransactions {

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactions.class);
    static final int MAX_ATTEMPTS = 5;

    private final TransactionTemplate template;

    public RetryingTransactions(PlatformTransactionManager tm) {
        this.template = new TransactionTemplate(tm);
    }

    public <T> T run(Supplier<T> work) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (ConcurrencyFailureException e) {
                last = e;
                log.debug("Concurrent modification, retrying (attempt {}/{})", attempt, MAX_ATTEMPTS);
                sleepBriefly(attempt);
            }
        }
        throw last;
    }

    public void runVoid(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }

    private static void sleepBriefly(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 25L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
