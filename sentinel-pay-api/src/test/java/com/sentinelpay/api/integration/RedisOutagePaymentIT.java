package com.sentinelpay.api.integration;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Behaviour of a payment while Redis is unreachable. PostgreSQL and RabbitMQ stay real; only the
 * Redis client is faulted, which is the honest way to exercise the adapter's degraded path without
 * disturbing the container the other integration tests share.
 *
 * <p>Documented outcome: the payment still commits and still publishes its event. Losing the cache
 * only downgrades duplicate protection from "replay the cached response" to "the unique constraint
 * on transactions.idempotency_key rejects the replay".
 */
@MockBean(StringRedisTemplate.class)
class RedisOutagePaymentIT extends AbstractIntegrationTest {

    private static final long EVENT_TIMEOUT_MS = 5_000;

    @Autowired ProcessPaymentUseCase processPaymentUseCase;

    @BeforeEach
    void simulateRedisOutage() {
        // keys() stays answerable so the shared cleanup in the base class still works; every
        // value operation the adapter performs fails the way a real outage would.
        when(stringRedisTemplate.keys(anyString())).thenReturn(Set.of());
        when(stringRedisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("simulated Redis outage"));
    }

    @Test
    void paymentStillCommitsAndPublishesWhileRedisIsDown() {
        UUID accountId = insertAccount("ACC-REDIS-001", "5000.00");

        PaymentResponse response = processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("400.00"), "LKR"), "idem-redis-001");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("4600.00");
        assertThat(countTransactions()).isEqualTo(1);
        assertThat(rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, EVENT_TIMEOUT_MS))
                .as("a cache outage must not cost the payment its event")
                .isNotNull();
    }

    @Test
    void duplicateProtectionFallsBackToTheUniqueConstraintWhileRedisIsDown() {
        UUID accountId = insertAccount("ACC-REDIS-002", "5000.00");
        PaymentCommand command = new PaymentCommand(accountId, new BigDecimal("400.00"), "LKR");

        processPaymentUseCase.process(command, "idem-redis-002");

        // With no cache to consult the replay reaches the database, where the unique index on
        // idempotency_key rejects it and the second debit rolls back.
        assertThatThrownBy(() -> processPaymentUseCase.process(command, "idem-redis-002"))
                .isInstanceOf(DataAccessException.class);

        assertThat(countTransactions()).isEqualTo(1);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("4600.00");
    }
}
