package com.sentinelpay.application.port.out;

import com.sentinelpay.domain.model.Transaction;

public interface TransactionRepositoryPort {
    Transaction save(Transaction transaction);
}
