package com.sentinelpay.domain.model;

import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class Transaction {
    private final UUID id;
    private final UUID accountId;
    private final Money amount;
    private TransactionStatus status;
    private RiskScore fraudScore;
    private String failureReason;
    private final Instant createdAt;
    private Instant processedAt;

    public Transaction(UUID id, UUID accountId, Money amount) {
        this.id = id;
        this.accountId = accountId;
        this.amount = amount;
        this.status = TransactionStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markAsProcessed(RiskScore score) {
        this.fraudScore = score;
        this.status = score.isHighRisk() ? TransactionStatus.REJECTED : TransactionStatus.COMPLETED;
        this.processedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = TransactionStatus.FAILED;
        this.failureReason = reason;
        this.processedAt = Instant.now();
    }
}