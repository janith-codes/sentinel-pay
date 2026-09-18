package com.sentinelpay.api.integration;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.application.port.out.AccountRepositoryPort;
import com.sentinelpay.application.port.out.TransactionRepositoryPort;
import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.model.Account;
import com.sentinelpay.domain.model.AccountStatus;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import com.sentinelpay.infrastructure.persistence.entity.AccountJpaEntity;
import com.sentinelpay.infrastructure.persistence.entity.TransactionJpaEntity;
import com.sentinelpay.infrastructure.persistence.repository.AccountJpaRepository;
import com.sentinelpay.infrastructure.persistence.repository.TransactionJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Persistence behaviour against real PostgreSQL: mapping, constraints, locking and rollback. */
class PersistenceIT extends AbstractIntegrationTest {

    @Autowired AccountRepositoryPort accountRepository;
    @Autowired TransactionRepositoryPort transactionRepository;
    @Autowired AccountJpaRepository accountJpaRepository;
    @Autowired TransactionJpaRepository transactionJpaRepository;
    @Autowired ProcessPaymentUseCase processPaymentUseCase;

    @Test
    void accountRoundTripsThroughTheRepositoryPort() {
        Account account = new Account(UUID.randomUUID(), "ACC-PERSIST-001",
                Money.of(2500.50), AccountStatus.ACTIVE);

        accountRepository.save(account);
        Optional<Account> loaded = accountRepository.findById(account.getId());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAccountNumber()).isEqualTo("ACC-PERSIST-001");
        assertThat(loaded.get().getBalance().amount()).isEqualByComparingTo("2500.50");
        assertThat(loaded.get().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void accountBalanceUpdatePersistsAndIncrementsVersion() {
        UUID accountId = insertAccount("ACC-PERSIST-002", "1000.00");

        Account account = accountRepository.findById(accountId).orElseThrow();
        account.debit(Money.of(250.00));
        accountRepository.save(account);

        assertThat(balanceOf(accountId)).isEqualByComparingTo("750.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM accounts WHERE id = ?", Long.class, accountId)).isEqualTo(1L);
    }

    @Test
    void transactionPersistsEveryMappedColumn() {
        UUID accountId = insertAccount("ACC-PERSIST-003", "5000.00");
        Transaction transaction = new Transaction(
                UUID.randomUUID(), accountId, Money.of(725.25), "LKR", "idem-persist-003");
        transaction.markAsProcessed(RiskScore.low("Amount within normal range"));

        transactionRepository.save(transaction);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM transactions WHERE id = ?", transaction.getId());

        assertThat(row.get("account_id")).isEqualTo(accountId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("725.25");
        assertThat(row.get("currency")).isEqualTo("LKR");
        assertThat(row.get("status")).isEqualTo(TransactionStatus.COMPLETED.name());
        assertThat(row.get("idempotency_key")).isEqualTo("idem-persist-003");
        assertThat(row.get("fraud_score")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("processed_at")).isNotNull();
    }

    @Test
    void foreignKeyRejectsTransactionForUnknownAccount() {
        TransactionJpaEntity orphan = TransactionJpaEntity.builder()
                .id(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .amount(new BigDecimal("10.00"))
                .currency("LKR")
                .status(TransactionStatus.PENDING)
                .build();

        assertThatThrownBy(() -> transactionJpaRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueIdempotencyKeyRejectsDuplicateTransactions() {
        UUID accountId = insertAccount("ACC-PERSIST-004", "5000.00");
        transactionRepository.save(new Transaction(
                UUID.randomUUID(), accountId, Money.of(100.00), "LKR", "idem-duplicate"));

        Transaction second = new Transaction(
                UUID.randomUUID(), accountId, Money.of(200.00), "LKR", "idem-duplicate");

        assertThatThrownBy(() -> transactionRepository.save(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optimisticLockingRejectsStaleAccountUpdate() {
        UUID accountId = insertAccount("ACC-PERSIST-005", "1000.00");

        // Two independent reads, each in its own transaction, both detached at version 0.
        AccountJpaEntity firstReader = accountJpaRepository.findById(accountId).orElseThrow();
        AccountJpaEntity secondReader = accountJpaRepository.findById(accountId).orElseThrow();
        assertThat(firstReader.getVersion()).isEqualTo(secondReader.getVersion());

        firstReader.setBalance(new BigDecimal("900.00"));
        accountJpaRepository.saveAndFlush(firstReader);

        secondReader.setBalance(new BigDecimal("800.00"));
        assertThatThrownBy(() -> accountJpaRepository.saveAndFlush(secondReader))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(balanceOf(accountId)).isEqualByComparingTo("900.00");
    }

    @Test
    void insufficientBalanceRollsBackTheWholePaymentTransaction() {
        UUID accountId = insertAccount("ACC-PERSIST-006", "100.00");
        PaymentCommand command = new PaymentCommand(accountId, new BigDecimal("500.00"), "LKR");

        assertThatThrownBy(() -> processPaymentUseCase.process(command, "idem-rollback"))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(countTransactions()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("100.00");
        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-rollback")).isNull();
    }
}
