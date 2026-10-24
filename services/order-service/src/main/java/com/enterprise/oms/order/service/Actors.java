package com.enterprise.oms.order.service;

import com.enterprise.oms.common.security.CurrentUser;

/** Who performed an action: the JWT subject of the caller, or a fixed name for internal actors. */
public final class Actors {

    /** Saga orchestrator reacting to events / validation results. */
    public static final String SAGA = "saga";
    /** Scheduled jobs (recovery, reconciliation). */
    public static final String SYSTEM = "system";

    private Actors() {
    }

    /** The authenticated caller, or {@code system} when there is none (scheduler, Kafka listener). */
    public static String current() {
        return CurrentUser.find().map(CurrentUser::id).orElse(SYSTEM);
    }
}
