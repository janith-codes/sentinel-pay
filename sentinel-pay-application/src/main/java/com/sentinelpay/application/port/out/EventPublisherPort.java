package com.sentinelpay.application.port.out;

import com.sentinelpay.domain.model.Transaction;

public interface EventPublisherPort {
    void publishPaymentProcessed(Transaction transaction);
}
