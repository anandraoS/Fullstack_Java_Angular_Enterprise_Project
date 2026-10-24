package com.enterprise.oms.common.security;

import com.enterprise.oms.common.error.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authenticated caller.
 *
 * @param id         JWT {@code sub} (or authentication name)
 * @param roles      role names without the ROLE_ prefix
 * @param customerId JWT {@code customerId} claim (CUSTOMER tokens) or null
 */
public record CurrentUser(String id, Set<String> roles, UUID customerId) {

    /** The current user, or empty when unauthenticated/anonymous. */
    public static Optional<CurrentUser> find() {
        return from(SecurityContextHolder.getContext().getAuthentication());
    }

    /** The current user; throws 403 if there is none (should not happen behind the security chain). */
    public static CurrentUser require() {
        return find().orElseThrow(() -> new ForbiddenException("No authenticated user"));
    }

    public static Optional<CurrentUser> from(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toUnmodifiableSet());
        UUID customerId = null;
        String id = auth.getName();
        if (auth.getPrincipal() instanceof Jwt jwt) {
            id = jwt.getSubject();
            String c = jwt.getClaimAsString("customerId");
            if (c != null) {
                try {
                    customerId = UUID.fromString(c);
                } catch (IllegalArgumentException ignored) {
                    // malformed claim: treated as absent
                }
            }
        }
        return Optional.of(new CurrentUser(id, roles, customerId));
    }

    public boolean hasRole(String role) { return roles.contains(role); }
    public boolean isAdmin() { return hasRole(Roles.ADMIN); }
    public boolean isOperator() { return hasRole(Roles.OPERATOR); }
    public boolean isCustomer() { return hasRole(Roles.CUSTOMER); }
    public boolean isService() { return hasRole(Roles.SERVICE); }
    /** OPERATOR or ADMIN: may see all customers' data. */
    public boolean isStaff() { return isAdmin() || isOperator(); }

    /** True if the caller is staff or is the customer identified by {@code ownerCustomerId}. */
    public boolean canAccessCustomer(UUID ownerCustomerId) {
        return isStaff() || (isCustomer() && customerId != null && customerId.equals(ownerCustomerId));
    }
}
