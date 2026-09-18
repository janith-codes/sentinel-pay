package com.sentinelpay.domain.identity;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String username, Set<Role> roles, UUID accountId) {

    public AuthenticatedUser {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /**
     * An ADMIN may act on any account; a USER is confined to the account linked to their identity.
     */
    public boolean canTransactOn(UUID targetAccountId) {
        if (hasRole(Role.ADMIN)) {
            return true;
        }
        return accountId != null && accountId.equals(targetAccountId);
    }
}
