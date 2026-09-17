package com.sentinelpay.infrastructure.persistence.entity;

import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskLevel;
import com.sentinelpay.domain.valueobject.RiskScore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "fraud_score")
    private Integer fraudScore;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Transaction toDomain() {
        Transaction tx = new Transaction(id, accountId, new Money(amount), currency, idempotencyKey);
        if (fraudScore != null) {
            tx.markAsProcessed(new RiskScore(fraudScore, RiskLevel.LOW, "restored"));
        }
        return tx;
    }

    public static TransactionJpaEntity fromDomain(Transaction tx) {
        return TransactionJpaEntity.builder()
                .id(tx.getId())
                .accountId(tx.getAccountId())
                .amount(tx.getAmount().amount())
                .currency(tx.getCurrency())
                .status(tx.getStatus())
                .fraudScore(tx.getFraudScore() != null ? tx.getFraudScore().score() : null)
                .failureReason(tx.getFailureReason())
                .idempotencyKey(tx.getIdempotencyKey())
                .createdAt(tx.getCreatedAt())
                .processedAt(tx.getProcessedAt())
                .build();
    }
}
