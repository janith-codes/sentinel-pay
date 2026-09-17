package com.sentinelpay.domain.model;

import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TransactionTest {

    private Transaction newTransaction() {
        return new Transaction(UUID.randomUUID(), UUID.randomUUID(), Money.of(500.0), "LKR", "key-123");
    }

    @Test
    void newTransaction_isPending() {
        assertThat(newTransaction().getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void markAsProcessed_withLowRisk_completesTransaction() {
        Transaction tx = newTransaction();
        tx.markAsProcessed(RiskScore.low("Normal transaction"));
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getProcessedAt()).isNotNull();
    }

    @Test
    void markAsProcessed_withHighRisk_rejectsTransaction() {
        Transaction tx = newTransaction();
        tx.markAsProcessed(RiskScore.high("Suspicious activity"));
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    void fail_setsFailedStatusWithReason() {
        Transaction tx = newTransaction();
        tx.fail("Account not found");
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureReason()).isEqualTo("Account not found");
    }
}
