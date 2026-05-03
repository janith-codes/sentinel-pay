package com.sentinelpay.domain.model;

import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.valueobject.Money;
import lombok.Getter;

import java.util.UUID;

@Getter
public class Account {
    private final UUID id;
    private final String accountNumber;
    private Money balance;
    private final AccountStatus status;
    private Long version;                    // for optimistic locking

    public Account(UUID id, String accountNumber, Money balance, AccountStatus status) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.status = status;
    }

    public void debit(Money amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account is not active");
        }
        if (balance.isLessThan(amount)) {
            throw new InsufficientBalanceException("Insufficient balance");
        }
        this.balance = balance.subtract(amount);
    }

    public void credit(Money amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account is not active");
        }
        this.balance = balance.add(amount);
    }
}