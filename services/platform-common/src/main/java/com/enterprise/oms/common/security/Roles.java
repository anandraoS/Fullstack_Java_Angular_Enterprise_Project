package com.enterprise.oms.common.security;

import java.util.Set;

/** Role names as they appear in the JWT {@code roles} claim (without the ROLE_ prefix). */
public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";
    public static final String SERVICE = "SERVICE";

    public static final Set<String> ALL = Set.of(CUSTOMER, OPERATOR, ADMIN, SERVICE);

    private Roles() {
    }
}
