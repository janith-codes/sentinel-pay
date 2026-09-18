package com.sentinelpay.infrastructure.persistence.entity;

import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // Eager: the role set is tiny and always needed on the authentication path,
    // where the domain object is mapped outside the persistence context.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public AppUser toDomain() {
        return new AppUser(id, username, passwordHash, roles, accountId, enabled);
    }

    public static UserJpaEntity fromDomain(AppUser user) {
        return UserJpaEntity.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .roles(new HashSet<>(user.getRoles()))
                .accountId(user.getAccountId())
                .enabled(user.isEnabled())
                .build();
    }
}
