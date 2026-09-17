package com.sentinelpay.infrastructure.messaging.event;

import com.sentinelpay.domain.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentProcessedEvent(
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        Integer fraudScore,
        Instant processedAt
) {
}
