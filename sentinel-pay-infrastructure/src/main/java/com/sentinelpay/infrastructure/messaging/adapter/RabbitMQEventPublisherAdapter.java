package com.sentinelpay.infrastructure.messaging.adapter;

import com.sentinelpay.application.port.out.EventPublisherPort;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import com.sentinelpay.infrastructure.messaging.event.PaymentProcessedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMQEventPublisherAdapter implements EventPublisherPort {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publishPaymentProcessed(Transaction transaction) {
        try {
            PaymentProcessedEvent event = new PaymentProcessedEvent(
                    transaction.getId(),
                    transaction.getAccountId(),
                    transaction.getAmount().amount(),
                    transaction.getCurrency(),
                    transaction.getStatus(),
                    transaction.getFraudScore() != null ? transaction.getFraudScore().score() : null,
                    transaction.getProcessedAt()
            );
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, event);
            log.info("Published payment event for tx: {}", transaction.getId());
        } catch (Exception e) {
            log.error("Failed to publish event for tx {}: {}", transaction.getId(), e.getMessage());
        }
    }
}
