package com.sentinelpay.api.integration;

import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.domain.identity.Role;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import com.sentinelpay.infrastructure.messaging.event.PaymentProcessedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end payment flow over real HTTP, exercising every layer with no infrastructure mocked:
 * HTTP -> JWT filter -> controller -> application service -> PostgreSQL -> Redis idempotency
 * -> rule-based fraud fallback -> transaction persistence -> RabbitMQ -> HTTP response.
 */
class PaymentFlowIT extends AbstractIntegrationTest {

    // Fixture value for the user created inside the throwaway PostgreSQL container.
    private static final String PASSWORD = "integration-test-payer-password";
    private static final long RECEIVE_TIMEOUT_MS = 5_000;

    @Test
    void completePaymentDebitsPersistsCachesAndPublishes() {
        UUID accountId = insertAccount("ACC-FLOW-001", "10000.00");
        insertUser("payer", PASSWORD, Role.USER, accountId);
        String token = accessTokenFor("payer", PASSWORD);
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<PaymentResponse> response =
                postPayment(token, idempotencyKey, accountId, "1500.00", PaymentResponse.class);

        // HTTP response
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TransactionStatus.COMPLETED);
        UUID transactionId = response.getBody().transactionId();

        // PostgreSQL: balance debited and transaction recorded
        assertThat(balanceOf(accountId)).isEqualByComparingTo("8500.00");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM transactions WHERE id = ?", transactionId);
        assertThat(row.get("account_id")).isEqualTo(accountId);
        assertThat(row.get("status")).isEqualTo(TransactionStatus.COMPLETED.name());
        assertThat(row.get("idempotency_key")).isEqualTo(idempotencyKey);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1500.00");

        // Redis: response cached under the idempotency key
        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + idempotencyKey)).isNotNull();

        // RabbitMQ: event published with the transaction details
        Object received = rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(received).isInstanceOf(PaymentProcessedEvent.class);
        PaymentProcessedEvent event = (PaymentProcessedEvent) received;
        assertThat(event.transactionId()).isEqualTo(transactionId);
        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.amount()).isEqualByComparingTo("1500.00");
        assertThat(event.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void duplicateIdempotencyKeyReplaysTheCachedResultWithoutDebitingTwice() {
        UUID accountId = insertAccount("ACC-FLOW-002", "10000.00");
        insertUser("payer", PASSWORD, Role.USER, accountId);
        String token = accessTokenFor("payer", PASSWORD);
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<PaymentResponse> first =
                postPayment(token, idempotencyKey, accountId, "2000.00", PaymentResponse.class);
        ResponseEntity<PaymentResponse> replay =
                postPayment(token, idempotencyKey, accountId, "2000.00", PaymentResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().transactionId()).isEqualTo(first.getBody().transactionId());

        assertThat(balanceOf(accountId)).isEqualByComparingTo("8000.00");
        assertThat(countTransactions()).isEqualTo(1);
    }

    @Test
    void distinctIdempotencyKeysDebitTheAccountTwice() {
        UUID accountId = insertAccount("ACC-FLOW-003", "10000.00");
        insertUser("payer", PASSWORD, Role.USER, accountId);
        String token = accessTokenFor("payer", PASSWORD);

        postPayment(token, UUID.randomUUID().toString(), accountId, "1000.00", PaymentResponse.class);
        postPayment(token, UUID.randomUUID().toString(), accountId, "2500.00", PaymentResponse.class);

        assertThat(balanceOf(accountId)).isEqualByComparingTo("6500.00");
        assertThat(countTransactions()).isEqualTo(2);
    }

    @Test
    void highRiskAmountIsRejectedByTheRuleBasedFallbackWithoutDebiting() {
        UUID accountId = insertAccount("ACC-FLOW-004", "900000.00");
        insertUser("payer", PASSWORD, Role.USER, accountId);
        String token = accessTokenFor("payer", PASSWORD);

        // Above the 500000 high-risk threshold of the fallback the AI adapter falls back to.
        ResponseEntity<PaymentResponse> response =
                postPayment(token, UUID.randomUUID().toString(), accountId, "600000.00", PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TransactionStatus.REJECTED);

        assertThat(balanceOf(accountId)).isEqualByComparingTo("900000.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM transactions WHERE id = ?", String.class,
                response.getBody().transactionId()))
                .isEqualTo(TransactionStatus.REJECTED.name());
    }

    @Test
    void paymentExceedingTheBalanceIsRejectedAndLeavesNoTrace() {
        UUID accountId = insertAccount("ACC-FLOW-005", "500.00");
        insertUser("payer", PASSWORD, Role.USER, accountId);
        String token = accessTokenFor("payer", PASSWORD);

        ResponseEntity<String> response =
                postPayment(token, UUID.randomUUID().toString(), accountId, "5000.00", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("/errors/insufficient-balance");

        assertThat(balanceOf(accountId)).isEqualByComparingTo("500.00");
        assertThat(countTransactions()).isZero();
    }
}
