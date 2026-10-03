package com.sentinelpay.application.port.out;

import com.sentinelpay.domain.model.Account;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepositoryPort {
    Optional<Account> findById(UUID accountId);
    Account save(Account account);
}
