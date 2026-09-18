package com.sentinelpay.api.integration;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import com.sentinelpay.infrastructure.messaging.event.PaymentProcessedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the commit/publish contract against real PostgreSQL, Redis and RabbitMQ: the payment event
 * becomes visible on the broker only once the database transaction has committed.
 */
class EventConsistencyIT extends AbstractIntegrationTest {

    private static final long EVENT_TIMEOUT_MS = 5_000;
    /** Short on purpose: nothing should be published at all, so there is nothing to wait for. */
    private static final long NO_EVENT_TIMEOUT_MS = 1_000;

    @Autowired ProcessPaymentUseCase processPaymentUseCase;
    @Autowired PlatformTransactionManager transactionManager;

    private PaymentProcessedEvent receiveEvent(long timeoutMs) {
        return (PaymentProcessedEvent) rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, timeoutMs);
    }

    @Test
    void committedPaymentPersistsTheRowAndPublishesTheEvent() {
        UUID accountId = insertAccount("ACC-EVT-001", "5000.00");

        PaymentResponse response = processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("1200.00"), "LKR"), "idem-evt-001");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(countTransactions()).isEqualTo(1);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("3800.00");

        PaymentProcessedEvent event = receiveEvent(EVENT_TIMEOUT_MS);
        assertThat(event).isNotNull();
        assertThat(event.transactionId()).isEqualTo(response.transactionId());
        assertThat(event.status()).isEqualTo(TransactionStatus.COMPLETED);

        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-evt-001")).isNotNull();
    }

    @Test
    void rolledBackPaymentPublishesNoEventAndCachesNothing() {
        UUID accountId = insertAccount("ACC-EVT-002", "100.00");

        assertThatThrownBy(() -> processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("900.00"), "LKR"), "idem-evt-002"))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(countTransactions()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("100.00");
        assertThat(receiveEvent(NO_EVENT_TIMEOUT_MS))
                .as("a rolled back payment must leave nothing on the broker")
                .isNull();
        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-evt-002")).isNull();
    }

    @Test
    void fraudRejectionIsABusinessOutcomeSoItStillCommitsAndPublishes() {
        UUID accountId = insertAccount("ACC-EVT-003", "900000.00");

        // Above the rule-based fallback's 500000 high-risk threshold.
        PaymentResponse response = processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("600000.00"), "LKR"), "idem-evt-003");

        assertThat(response.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("900000.00");
        assertThat(countTransactions()).isEqualTo(1);

        PaymentProcessedEvent event = receiveEvent(EVENT_TIMEOUT_MS);
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(TransactionStatus.REJECTED);
    }

    /**
     * The use case joins the surrounding transaction, so the commit is deferred to the outer
     * boundary. That makes the ordering directly observable: nothing may reach the broker while
     * the transaction is still open.
     */
    @Test
    void eventStaysOffTheBrokerUntilTheSurroundingTransactionCommits() {
        UUID accountId = insertAccount("ACC-EVT-004", "5000.00");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        UUID transactionId = transactionTemplate.execute(status -> {
            PaymentResponse response = processPaymentUseCase.process(
                    new PaymentCommand(accountId, new BigDecimal("250.00"), "LKR"), "idem-evt-004");

            assertThat(receiveEvent(NO_EVENT_TIMEOUT_MS))
                    .as("the transaction is still open, so the event must not be published yet")
                    .isNull();
            assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-evt-004"))
                    .as("the response must not be cached before the payment is durable")
                    .isNull();

            return response.transactionId();
        });

        PaymentProcessedEvent event = receiveEvent(EVENT_TIMEOUT_MS);
        assertThat(event).as("the event must appear once the transaction commits").isNotNull();
        assertThat(event.transactionId()).isEqualTo(transactionId);
        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-evt-004")).isNotNull();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("4750.00");
    }

    /**
     * Same ordering seen from the failure side: work done inside the surrounding transaction is
     * discarded, and no post-commit effect ever runs.
     */
    @Test
    void rollingBackTheSurroundingTransactionSuppressesEveryPostCommitEffect() {
        UUID accountId = insertAccount("ACC-EVT-005", "5000.00");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.execute(status -> {
            processPaymentUseCase.process(
                    new PaymentCommand(accountId, new BigDecimal("300.00"), "LKR"), "idem-evt-005");
            status.setRollbackOnly();
            return null;
        });

        assertThat(countTransactions()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("5000.00");
        assertThat(receiveEvent(NO_EVENT_TIMEOUT_MS)).isNull();
        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-evt-005")).isNull();
    }
}
