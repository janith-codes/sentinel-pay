package com.sentinelpay.domain.identity;

import lombok.Getter;

import java.util.Set;
import java.util.UUID;

@Getter
public class AppUser {

    private final UUID id;
    private final String username;
    private final String passwordHash;
    private final Set<Role> roles;
    private final UUID accountId;
    private final boolean enabled;

    public AppUser(UUID id, String username, String passwordHash, Set<Role> roles, UUID accountId, boolean enabled) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash is required");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = Set.copyOf(roles);
        this.accountId = accountId;
        this.enabled = enabled;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public AuthenticatedUser asAuthenticated() {
        return new AuthenticatedUser(id, username, roles, accountId);
    }
}
