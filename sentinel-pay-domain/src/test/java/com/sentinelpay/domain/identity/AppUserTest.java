package com.sentinelpay.domain.identity;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppUserTest {

    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuv";

    @Test
    void constructor_createsUserWithRolesAndLinkedAccount() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        AppUser user = new AppUser(id, "janith", HASH, Set.of(Role.USER), accountId, true);

        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getUsername()).isEqualTo("janith");
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
        assertThat(user.getAccountId()).isEqualTo(accountId);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getRoles()).containsExactly(Role.USER);
    }

    @Test
    void constructor_rejectsBlankUsername() {
        assertThatThrownBy(() -> new AppUser(UUID.randomUUID(), "  ", HASH, Set.of(Role.USER), null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void constructor_rejectsBlankPasswordHash() {
        assertThatThrownBy(() -> new AppUser(UUID.randomUUID(), "janith", "", Set.of(Role.USER), null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordHash");
    }

    @Test
    void constructor_rejectsEmptyRoles() {
        assertThatThrownBy(() -> new AppUser(UUID.randomUUID(), "janith", HASH, Set.of(), null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    void constructor_rejectsNullId() {
        assertThatThrownBy(() -> new AppUser(null, "janith", HASH, Set.of(Role.USER), null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void hasRole_reflectsAssignedRoles() {
        AppUser admin = new AppUser(UUID.randomUUID(), "admin", HASH, Set.of(Role.ADMIN), null, true);

        assertThat(admin.hasRole(Role.ADMIN)).isTrue();
        assertThat(admin.hasRole(Role.USER)).isFalse();
    }

    @Test
    void asAuthenticated_carriesIdentityWithoutPasswordHash() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AppUser user = new AppUser(id, "janith", HASH, Set.of(Role.USER, Role.ADMIN), accountId, true);

        AuthenticatedUser authenticated = user.asAuthenticated();

        assertThat(authenticated.userId()).isEqualTo(id);
        assertThat(authenticated.username()).isEqualTo("janith");
        assertThat(authenticated.accountId()).isEqualTo(accountId);
        assertThat(authenticated.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    void getRoles_returnsImmutableSet() {
        AppUser user = new AppUser(UUID.randomUUID(), "janith", HASH, Set.of(Role.USER), null, true);

        assertThatThrownBy(() -> user.getRoles().add(Role.ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
