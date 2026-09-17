package com.sentinelpay.domain.model;

import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AccountTest {

    private Account activeAccount(double balance) {
        return new Account(UUID.randomUUID(), "ACC-001", Money.of(balance), AccountStatus.ACTIVE);
    }

    @Test
    void debit_reducesBalance() {
        Account account = activeAccount(1000.0);
        account.debit(Money.of(300.0));
        assertThat(account.getBalance().amount()).isEqualByComparingTo(new BigDecimal("700.0"));
    }

    @Test
    void debit_throwsWhenInsufficientBalance() {
        Account account = activeAccount(100.0);
        assertThatThrownBy(() -> account.debit(Money.of(500.0)))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void debit_throwsWhenAccountNotActive() {
        Account account = new Account(UUID.randomUUID(), "ACC-002", Money.of(1000.0), AccountStatus.SUSPENDED);
        assertThatThrownBy(() -> account.debit(Money.of(100.0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void credit_increasesBalance() {
        Account account = activeAccount(500.0);
        account.credit(Money.of(250.0));
        assertThat(account.getBalance().amount()).isEqualByComparingTo(new BigDecimal("750.0"));
    }

    @Test
    void credit_throwsWhenAccountNotActive() {
        Account account = new Account(UUID.randomUUID(), "ACC-003", Money.of(500.0), AccountStatus.FROZEN);
        assertThatThrownBy(() -> account.credit(Money.of(100.0)))
                .isInstanceOf(IllegalStateException.class);
    }
}
