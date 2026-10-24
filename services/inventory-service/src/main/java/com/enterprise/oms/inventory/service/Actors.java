package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.security.CurrentUser;

/** Who made a change: the authenticated principal, or {@code system} for consumers and schedulers. */
public final class Actors {

    private Actors() {
    }

    public static String current() {
        return CurrentUser.find().map(CurrentUser::id).orElse("system");
    }
}
