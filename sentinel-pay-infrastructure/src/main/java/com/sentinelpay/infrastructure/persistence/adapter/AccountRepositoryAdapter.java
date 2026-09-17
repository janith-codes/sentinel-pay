package com.sentinelpay.infrastructure.persistence.adapter;

import com.sentinelpay.application.port.out.AccountRepositoryPort;
import com.sentinelpay.domain.model.Account;
import com.sentinelpay.infrastructure.persistence.entity.AccountJpaEntity;
import com.sentinelpay.infrastructure.persistence.repository.AccountJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccountRepositoryAdapter implements AccountRepositoryPort {

    private final AccountJpaRepository repository;

    @Override
    public Optional<Account> findById(UUID accountId) {
        return repository.findById(accountId).map(AccountJpaEntity::toDomain);
    }

    @Override
    public Account save(Account account) {
        return repository.findById(account.getId())
                .map(entity -> {
                    entity.setBalance(account.getBalance().amount());
                    entity.setStatus(account.getStatus());
                    return repository.save(entity).toDomain();
                })
                .orElseGet(() -> repository.save(AccountJpaEntity.fromDomain(account)).toDomain());
    }
}
