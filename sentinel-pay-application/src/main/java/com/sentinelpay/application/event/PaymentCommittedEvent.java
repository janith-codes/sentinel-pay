package com.sentinelpay.application.event;

import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.domain.model.Transaction;

/**
 * In-process signal raised while a payment's database work is still uncommitted. Consumed only
 * after that transaction commits, which is what keeps broker and cache writes out of the
 * rollback window.
 */
public record PaymentCommittedEvent(
        Transaction transaction,
        String idempotencyKey,
        PaymentResponse response
) {
}
