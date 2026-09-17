package com.sentinelpay.application.service;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.application.port.out.*;
import com.sentinelpay.domain.exception.AccountNotFoundException;
import com.sentinelpay.domain.model.Account;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final AccountRepositoryPort accountRepository;
    private final TransactionRepositoryPort transactionRepository;
    private final FraudDetectionPort fraudDetection;
    private final IdempotencyPort idempotencyPort;
    private final EventPublisherPort eventPublisher;

    @Override
    public PaymentResponse process(PaymentCommand command, String idempotencyKey) {
        var cached = idempotencyPort.get(idempotencyKey);
        if (cached.isPresent()) {
            log.info("Duplicate request detected for idempotency key: {}", idempotencyKey);
            return cached.get();
        }

        Account account = accountRepository.findById(command.accountId())
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                command.accountId(),
                new Money(command.amount()),
                command.currency(),
                idempotencyKey
        );

        try {
            RiskScore riskScore = fraudDetection.evaluate(command);
            log.info("Fraud assessment for tx {}: score={}, level={}", transaction.getId(), riskScore.score(), riskScore.level());

            transaction.markAsProcessed(riskScore);

            if (transaction.getStatus() == TransactionStatus.COMPLETED) {
                account.debit(new Money(command.amount()));
                accountRepository.save(account);
            }
        } catch (Exception e) {
            log.error("Payment processing failed for tx {}: {}", transaction.getId(), e.getMessage());
            transaction.fail(e.getMessage());
        }

        transactionRepository.save(transaction);
        eventPublisher.publishPaymentProcessed(transaction);

        PaymentResponse response = new PaymentResponse(
                transaction.getId(),
                transaction.getStatus(),
                resolveMessage(transaction.getStatus())
        );
        idempotencyPort.store(idempotencyKey, response, Duration.ofHours(24));

        return response;
    }

    private String resolveMessage(TransactionStatus status) {
        return switch (status) {
            case COMPLETED -> "Payment processed successfully";
            case REJECTED  -> "Payment rejected due to high fraud risk";
            case FAILED    -> "Payment processing failed";
            default        -> "Payment is " + status.name().toLowerCase();
        };
    }
}
