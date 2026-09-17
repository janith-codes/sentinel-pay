package com.sentinelpay.infrastructure.persistence.adapter;

import com.sentinelpay.application.port.out.TransactionRepositoryPort;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.infrastructure.persistence.entity.TransactionJpaEntity;
import com.sentinelpay.infrastructure.persistence.repository.TransactionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransactionRepositoryAdapter implements TransactionRepositoryPort {

    private final TransactionJpaRepository repository;

    @Override
    public Transaction save(Transaction transaction) {
        return repository.save(TransactionJpaEntity.fromDomain(transaction)).toDomain();
    }
}
