package com.sentinelpay.domain.identity;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTest {

    private final UUID ownAccount = UUID.randomUUID();
    private final UUID foreignAccount = UUID.randomUUID();

    @Test
    void canTransactOn_allowsUserOnOwnAccount() {
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "janith", Set.of(Role.USER), ownAccount);

        assertThat(user.canTransactOn(ownAccount)).isTrue();
    }

    @Test
    void canTransactOn_deniesUserOnForeignAccount() {
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "janith", Set.of(Role.USER), ownAccount);

        assertThat(user.canTransactOn(foreignAccount)).isFalse();
    }

    @Test
    void canTransactOn_deniesUserWithoutLinkedAccount() {
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "janith", Set.of(Role.USER), null);

        assertThat(user.canTransactOn(foreignAccount)).isFalse();
    }

    @Test
    void canTransactOn_allowsAdminOnAnyAccount() {
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "admin", Set.of(Role.ADMIN), null);

        assertThat(admin.canTransactOn(foreignAccount)).isTrue();
        assertThat(admin.canTransactOn(ownAccount)).isTrue();
    }

    @Test
    void hasRole_reflectsGrantedRoles() {
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "janith", Set.of(Role.USER), ownAccount);

        assertThat(user.hasRole(Role.USER)).isTrue();
        assertThat(user.hasRole(Role.ADMIN)).isFalse();
    }

    @Test
    void constructor_defaultsNullRolesToEmptySet() {
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "janith", null, ownAccount);

        assertThat(user.roles()).isEmpty();
        assertThat(user.hasRole(Role.USER)).isFalse();
    }

    @Test
    void constructor_rejectsMissingIdentity() {
        assertThatThrownBy(() -> new AuthenticatedUser(null, "janith", Set.of(Role.USER), ownAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> new AuthenticatedUser(UUID.randomUUID(), " ", Set.of(Role.USER), ownAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }
}
