package com.sentinelpay.infrastructure.persistence.entity;

import com.sentinelpay.domain.model.Account;
import com.sentinelpay.domain.model.AccountStatus;
import com.sentinelpay.domain.valueobject.Money;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Version
    @Column(name = "version")
    private Long version;

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

    public Account toDomain() {
        return new Account(id, accountNumber, new Money(balance), status);
    }

    public static AccountJpaEntity fromDomain(Account account) {
        return AccountJpaEntity.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .balance(account.getBalance().amount())
                .status(account.getStatus())
                .build();
    }
}
