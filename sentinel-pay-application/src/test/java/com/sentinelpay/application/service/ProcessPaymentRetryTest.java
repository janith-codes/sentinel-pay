package com.sentinelpay.application.service;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.event.PaymentCommittedEvent;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.application.port.out.AccountRepositoryPort;
import com.sentinelpay.application.port.out.FraudDetectionPort;
import com.sentinelpay.application.port.out.IdempotencyPort;
import com.sentinelpay.application.port.out.TransactionRepositoryPort;
import com.sentinelpay.domain.exception.AccountNotFoundException;
import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.model.Account;
import com.sentinelpay.domain.model.AccountStatus;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the retry policy through real Spring proxies. {@code @Retryable} and
 * {@code @Transactional} are both advice, so a plain Mockito test on the bare object would exercise
 * neither of them.
 *
 * <p>The context mirrors production ordering: retry at {@code LOWEST_PRECEDENCE - 1} outside the
 * transaction advice at {@code LOWEST_PRECEDENCE}. A mocked transaction manager makes each
 * transaction's begin, commit and rollback observable without a database.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ProcessPaymentRetryTest.RetryPolicyConfig.class)
class ProcessPaymentRetryTest {

    @EnableTransactionManagement
    @EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
    @Configuration
    static class RetryPolicyConfig {

        /**
         * Handed to the service directly: the context always resolves ApplicationEventPublisher to
         * itself, so a mock registered as a bean would never be injected.
         */
        static final ApplicationEventPublisher EVENTS = Mockito.mock(ApplicationEventPublisher.class);

        @Bean PlatformTransactionManager transactionManager() {
            return Mockito.mock(PlatformTransactionManager.class);
        }

        @Bean AccountRepositoryPort accountRepository() {
            return Mockito.mock(AccountRepositoryPort.class);
        }

        @Bean TransactionRepositoryPort transactionRepository() {
            return Mockito.mock(TransactionRepositoryPort.class);
        }

        @Bean FraudDetectionPort fraudDetection() {
            return Mockito.mock(FraudDetectionPort.class);
        }

        @Bean IdempotencyPort idempotencyPort() {
            return Mockito.mock(IdempotencyPort.class);
        }

        @Bean ProcessPaymentUseCase processPaymentUseCase(AccountRepositoryPort accountRepository,
                                                          TransactionRepositoryPort transactionRepository,
                                                          FraudDetectionPort fraudDetection,
                                                          IdempotencyPort idempotencyPort) {
            return new ProcessPaymentService(accountRepository, transactionRepository,
                    fraudDetection, idempotencyPort, EVENTS);
        }
    }

    private static final ApplicationEventPublisher EVENTS = RetryPolicyConfig.EVENTS;

    @Autowired ProcessPaymentUseCase useCase;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AccountRepositoryPort accountRepository;
    @Autowired TransactionRepositoryPort transactionRepository;
    @Autowired FraudDetectionPort fraudDetection;
    @Autowired IdempotencyPort idempotencyPort;

    private final UUID accountId = UUID.randomUUID();
    private PaymentCommand command;

    /** A new instance per call, the way each real transaction reloads the row it will update. */
    private Account freshAccount(String balance) {
        return new Account(accountId, "ACC-RETRY", Money.of(Double.parseDouble(balance)), AccountStatus.ACTIVE);
    }

    @BeforeEach
    void resetCollaborators() {
        Mockito.reset(transactionManager, accountRepository, transactionRepository,
                fraudDetection, idempotencyPort, EVENTS);

        command = new PaymentCommand(accountId, new BigDecimal("400.00"), "LKR");
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(idempotencyPort.get(anyString())).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findById(accountId)).thenAnswer(inv -> Optional.of(freshAccount("1000.00")));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fraudDetection.evaluate(any())).thenReturn(RiskScore.low("Amount within normal range"));
    }

    private OptimisticLockingFailureException conflict() {
        return new OptimisticLockingFailureException("account version changed");
    }

    private List<PaymentCommittedEvent> publishedEvents(int expected) {
        ArgumentCaptor<PaymentCommittedEvent> captor = ArgumentCaptor.forClass(PaymentCommittedEvent.class);
        verify(EVENTS, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    // ---------------------------------------------------------------- retried

    @Test
    void successfulPaymentRunsOnceInASingleTransaction() {
        PaymentResponse response = useCase.process(command, "key-no-retry");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());
        verify(transactionManager, never()).rollback(any());
        verify(accountRepository, times(1)).findById(accountId);

        PaymentCommittedEvent event = publishedEvents(1).get(0);
        assertThat(event.transaction().getId()).isEqualTo(response.transactionId());
    }

    @Test
    void conflictOnTheFirstAttemptRollsBackThenRetriesInANewTransaction() {
        when(accountRepository.save(any()))
                .thenThrow(conflict())
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = useCase.process(command, "key-retry-once");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        // The failed transaction is rolled back before the retry opens a new one.
        InOrder transactions = inOrder(transactionManager);
        transactions.verify(transactionManager).getTransaction(any());
        transactions.verify(transactionManager).rollback(any());
        transactions.verify(transactionManager).getTransaction(any());
        transactions.verify(transactionManager).commit(any());
        transactions.verifyNoMoreInteractions();

        // Each attempt reloads the account, which is where it picks up the current version.
        verify(accountRepository, times(2)).findById(accountId);

        // Only the attempt that got through raised the post-commit event.
        PaymentCommittedEvent event = publishedEvents(1).get(0);
        assertThat(event.transaction().getId()).isEqualTo(response.transactionId());
    }

    @Test
    void conflictOnTheFirstTwoAttemptsSucceedsOnTheThird() {
        when(accountRepository.save(any()))
                .thenThrow(conflict())
                .thenThrow(conflict())
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = useCase.process(command, "key-retry-twice");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        verify(transactionManager, times(3)).getTransaction(any());
        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        verify(accountRepository, times(3)).findById(accountId);

        PaymentCommittedEvent event = publishedEvents(1).get(0);
        assertThat(event.transaction().getId()).isEqualTo(response.transactionId());
    }

    @Test
    void conflictOnEveryAttemptPropagatesAfterTheAttemptLimit() {
        when(accountRepository.save(any())).thenThrow(conflict());

        assertThatThrownBy(() -> useCase.process(command, "key-retry-exhausted"))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(transactionManager, times(ProcessPaymentService.MAX_ATTEMPTS)).getTransaction(any());
        verify(transactionManager, times(ProcessPaymentService.MAX_ATTEMPTS)).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(accountRepository, times(ProcessPaymentService.MAX_ATTEMPTS)).findById(accountId);
        verify(EVENTS, never()).publishEvent(any());
    }

    /**
     * The production failure mode: the version check fails while committing, after the method has
     * returned. Being retried at all proves the retry advice wraps the transaction advice.
     *
     * <p>Both bodies ran to completion here, so each raised its in-process event. Which of them
     * reaches the broker is decided by the real transaction synchronization: only the committed
     * attempt's AFTER_COMMIT handler runs, as OptimisticLockRetryIT verifies against PostgreSQL.
     */
    @Test
    void conflictRaisedWhileCommittingIsRetriedInANewTransaction() {
        doThrow(conflict()).doNothing().when(transactionManager).commit(any());

        PaymentResponse response = useCase.process(command, "key-commit-conflict");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        InOrder transactions = inOrder(transactionManager);
        transactions.verify(transactionManager).getTransaction(any());
        transactions.verify(transactionManager).commit(any());
        transactions.verify(transactionManager).getTransaction(any());
        transactions.verify(transactionManager).commit(any());
        transactions.verifyNoMoreInteractions();

        verify(accountRepository, times(2)).findById(accountId);

        List<PaymentCommittedEvent> events = publishedEvents(2);
        assertThat(events.get(1).transaction().getId()).isEqualTo(response.transactionId());
        assertThat(events.get(0).transaction().getId()).isNotEqualTo(response.transactionId());
    }

    @Test
    void retryReusesTheCallersIdempotencyKeyAndLeavesCachingToThePostCommitHandler() {
        when(accountRepository.save(any()))
                .thenThrow(conflict())
                .thenAnswer(inv -> inv.getArgument(0));

        useCase.process(command, "key-stable");

        verify(idempotencyPort, times(2)).get("key-stable");
        verify(idempotencyPort, never()).get(Mockito.argThat(key -> !"key-stable".equals(key)));
        verify(idempotencyPort, never()).store(anyString(), any(), any());
        assertThat(publishedEvents(1).get(0).idempotencyKey()).isEqualTo("key-stable");
        verify(transactionRepository, times(1)).save(Mockito.argThat(tx -> "key-stable".equals(tx.getIdempotencyKey())));
    }

    // ---------------------------------------------------------------- not retried

    @Test
    void insufficientBalanceIsNotRetried() {
        when(accountRepository.findById(accountId)).thenAnswer(inv -> Optional.of(freshAccount("100.00")));

        assertThatThrownBy(() -> useCase.process(command, "key-insufficient"))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).rollback(any());
        verify(accountRepository, times(1)).findById(accountId);
        verify(EVENTS, never()).publishEvent(any());
    }

    @Test
    void accountNotFoundIsNotRetried() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.process(command, "key-missing-account"))
                .isInstanceOf(AccountNotFoundException.class);

        verify(transactionManager, times(1)).getTransaction(any());
        verify(accountRepository, times(1)).findById(accountId);
        verify(EVENTS, never()).publishEvent(any());
    }

    @Test
    void fraudRejectionIsNotRetried() {
        when(fraudDetection.evaluate(any())).thenReturn(RiskScore.high("Amount exceeds high-risk threshold"));

        PaymentResponse response = useCase.process(command, "key-rejected");

        // A rejection is a committed business outcome, not a failure.
        assertThat(response.status()).isEqualTo(TransactionStatus.REJECTED);
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());
        verify(fraudDetection, times(1)).evaluate(any());
        verify(accountRepository, never()).save(any());
        assertThat(publishedEvents(1).get(0).transaction().getStatus()).isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    void genericRuntimeExceptionIsNotRetried() {
        when(transactionRepository.save(any())).thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> useCase.process(command, "key-generic"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");

        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).rollback(any());
        verify(EVENTS, never()).publishEvent(any());
    }

    @Test
    void otherConcurrencyFailuresAreNotRetried() {
        when(transactionRepository.save(any()))
                .thenThrow(new PessimisticLockingFailureException("lock wait timeout"));

        assertThatThrownBy(() -> useCase.process(command, "key-pessimistic"))
                .isInstanceOf(PessimisticLockingFailureException.class);

        verify(transactionManager, times(1)).getTransaction(any());
        verify(accountRepository, times(1)).findById(accountId);
    }

    @Test
    void failureDuringProcessingIsRecordedAsFailedWithoutRetry() {
        when(accountRepository.save(any())).thenThrow(new IllegalArgumentException("not a lock conflict"));

        PaymentResponse response = useCase.process(command, "key-absorbed");

        assertThat(response.status()).isEqualTo(TransactionStatus.FAILED);
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager, times(1)).commit(any());
        verify(accountRepository, times(1)).save(any());
        verify(idempotencyPort, times(1)).get(eq("key-absorbed"));
    }
}
