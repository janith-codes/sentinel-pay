package com.sentinelpay.application.service;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.event.PaymentCommittedEvent;
import com.sentinelpay.application.port.out.*;
import com.sentinelpay.domain.exception.AccountNotFoundException;
import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.model.Account;
import com.sentinelpay.domain.model.AccountStatus;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessPaymentServiceTest {

    @Mock AccountRepositoryPort accountRepository;
    @Mock TransactionRepositoryPort transactionRepository;
    @Mock FraudDetectionPort fraudDetection;
    @Mock IdempotencyPort idempotencyPort;
    @Mock ApplicationEventPublisher applicationEvents;

    @InjectMocks
    ProcessPaymentService service;

    private Account testAccount;
    private PaymentCommand command;

    @BeforeEach
    void setUp() {
        testAccount = new Account(UUID.randomUUID(), "ACC-001", Money.of(10000.0), AccountStatus.ACTIVE);
        command = new PaymentCommand(testAccount.getId(), new BigDecimal("500.00"), "LKR");
        when(idempotencyPort.get(anyString())).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void process_completesPaymentForLowRisk() {
        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
        when(fraudDetection.evaluate(command)).thenReturn(RiskScore.low("Normal"));
        when(accountRepository.save(any())).thenReturn(testAccount);

        PaymentResponse response = service.process(command, "key-001");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        verify(accountRepository).save(testAccount);

        // Publishing and caching are the post-commit handler's job; the service only requests them.
        ArgumentCaptor<PaymentCommittedEvent> event = ArgumentCaptor.forClass(PaymentCommittedEvent.class);
        verify(applicationEvents).publishEvent(event.capture());
        assertThat(event.getValue().idempotencyKey()).isEqualTo("key-001");
        assertThat(event.getValue().response()).isEqualTo(response);
        assertThat(event.getValue().transaction().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void process_requestsNoPostCommitEffectsWhenBalanceIsInsufficient() {
        Account lowBalance = new Account(UUID.randomUUID(), "ACC-002", Money.of(100.0), AccountStatus.ACTIVE);
        PaymentCommand tooLarge = new PaymentCommand(lowBalance.getId(), new BigDecimal("500.00"), "LKR");
        when(accountRepository.findById(lowBalance.getId())).thenReturn(Optional.of(lowBalance));
        when(fraudDetection.evaluate(tooLarge)).thenReturn(RiskScore.low("Normal"));

        assertThatThrownBy(() -> service.process(tooLarge, "key-low-balance"))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(applicationEvents);
    }

    @Test
    void process_rejectsPaymentForHighRisk() {
        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
        when(fraudDetection.evaluate(command)).thenReturn(RiskScore.high("Suspicious"));

        PaymentResponse response = service.process(command, "key-002");

        assertThat(response.status()).isEqualTo(TransactionStatus.REJECTED);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void process_returnsIdempotentResponseOnDuplicateKey() {
        PaymentResponse cached = new PaymentResponse(UUID.randomUUID(), TransactionStatus.COMPLETED, "Payment processed successfully");
        when(idempotencyPort.get("dup-key")).thenReturn(Optional.of(cached));

        PaymentResponse response = service.process(command, "dup-key");

        assertThat(response).isEqualTo(cached);
        verifyNoInteractions(accountRepository, fraudDetection, transactionRepository, applicationEvents);
    }

    @Test
    void process_throwsWhenAccountNotFound() {
        when(accountRepository.findById(command.accountId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(command, "key-003"))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(applicationEvents);
    }

    @Test
    void process_savesTransactionWithIdempotencyKey() {
        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
        when(fraudDetection.evaluate(command)).thenReturn(RiskScore.low("Normal"));
        when(accountRepository.save(any())).thenReturn(testAccount);

        service.process(command, "key-idem");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getIdempotencyKey()).isEqualTo("key-idem");
    }
}
