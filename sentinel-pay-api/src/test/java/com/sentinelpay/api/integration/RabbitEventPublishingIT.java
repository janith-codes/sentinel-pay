package com.sentinelpay.api.integration;

import com.sentinelpay.application.port.out.EventPublisherPort;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import com.sentinelpay.infrastructure.messaging.event.PaymentProcessedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Event publishing against a real RabbitMQ container. */
class RabbitEventPublishingIT extends AbstractIntegrationTest {

    private static final long RECEIVE_TIMEOUT_MS = 5_000;

    @Autowired EventPublisherPort eventPublisher;

    private Transaction completedTransaction(UUID accountId, String amount) {
        Transaction transaction = new Transaction(
                UUID.randomUUID(), accountId, Money.of(Double.parseDouble(amount)), "LKR",
                "idem-" + UUID.randomUUID());
        transaction.markAsProcessed(RiskScore.low("Amount within normal range"));
        return transaction;
    }

    @Test
    void applicationDeclaredTheExchangeQueueAndBinding() {
        Properties queueProperties = amqpAdmin.getQueueProperties(RabbitMQConfig.QUEUE);

        assertThat(queueProperties)
                .as("queue %s must be declared on startup", RabbitMQConfig.QUEUE)
                .isNotNull();
        assertThat(queueProperties.get("QUEUE_NAME")).isEqualTo(RabbitMQConfig.QUEUE);

        // A message routed through the topic exchange only lands in the queue if the binding exists.
        Transaction transaction = completedTransaction(UUID.randomUUID(), "10.00");
        eventPublisher.publishPaymentProcessed(transaction);

        Object received = rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(received)
                .as("binding %s -> %s must route the event", RabbitMQConfig.EXCHANGE, RabbitMQConfig.QUEUE)
                .isNotNull();
    }

    @Test
    void publishedEventCarriesTheTransactionDetails() {
        UUID accountId = UUID.randomUUID();
        Transaction transaction = completedTransaction(accountId, "1250.75");

        eventPublisher.publishPaymentProcessed(transaction);

        Object received = rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, RECEIVE_TIMEOUT_MS);

        assertThat(received).isInstanceOf(PaymentProcessedEvent.class);
        PaymentProcessedEvent event = (PaymentProcessedEvent) received;
        assertThat(event.transactionId()).isEqualTo(transaction.getId());
        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.amount()).isEqualByComparingTo("1250.75");
        assertThat(event.currency()).isEqualTo("LKR");
        assertThat(event.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(event.fraudScore()).isEqualTo(transaction.getFraudScore().score());
        assertThat(event.processedAt()).isNotNull();
    }

    @Test
    void eventIsSerializedAsJsonWithATypeHeader() {
        eventPublisher.publishPaymentProcessed(completedTransaction(UUID.randomUUID(), "99.99"));

        Message message = rabbitTemplate.receive(RabbitMQConfig.QUEUE, RECEIVE_TIMEOUT_MS);

        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getHeaders().get("__TypeId__"))
                .isEqualTo(PaymentProcessedEvent.class.getName());
        assertThat(new String(message.getBody()))
                .contains("\"currency\":\"LKR\"")
                .contains("\"status\":\"COMPLETED\"")
                .contains("\"amount\":99.99");
    }

    @Test
    void rejectedTransactionIsPublishedWithItsRejectedStatus() {
        Transaction transaction = new Transaction(
                UUID.randomUUID(), UUID.randomUUID(), Money.of(750000.00), "LKR",
                "idem-" + UUID.randomUUID());
        transaction.markAsProcessed(RiskScore.high("Amount exceeds high-risk threshold"));

        eventPublisher.publishPaymentProcessed(transaction);

        PaymentProcessedEvent event =
                (PaymentProcessedEvent) rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, RECEIVE_TIMEOUT_MS);

        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo(TransactionStatus.REJECTED);
    }
}
