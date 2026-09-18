package com.sentinelpay.application.event;

import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.out.EventPublisherPort;
import com.sentinelpay.application.port.out.IdempotencyPort;
import com.sentinelpay.domain.model.Transaction;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.domain.valueobject.Money;
import com.sentinelpay.domain.valueobject.RiskScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentPostCommitHandlerTest {

    @Mock EventPublisherPort eventPublisher;
    @Mock IdempotencyPort idempotencyPort;

    @InjectMocks
    PaymentPostCommitHandler handler;

    private PaymentCommittedEvent committedEvent(String idempotencyKey) {
        Transaction transaction = new Transaction(
                UUID.randomUUID(), UUID.randomUUID(), Money.of(500.0), "LKR", idempotencyKey);
        transaction.markAsProcessed(RiskScore.low("Amount within normal range"));

        PaymentResponse response = new PaymentResponse(
                transaction.getId(), TransactionStatus.COMPLETED, "Payment processed successfully");

        return new PaymentCommittedEvent(transaction, idempotencyKey, response);
    }

    @Test
    void onPaymentCommitted_publishesTheTransactionThenCachesTheResponse() {
        PaymentCommittedEvent event = committedEvent("key-post-commit");

        handler.onPaymentCommitted(event);

        InOrder inOrder = Mockito.inOrder(eventPublisher, idempotencyPort);
        inOrder.verify(eventPublisher).publishPaymentProcessed(event.transaction());
        inOrder.verify(idempotencyPort).store(eq("key-post-commit"), eq(event.response()), any(Duration.class));
    }

    @Test
    void onPaymentCommitted_cachesForATwentyFourHourIdempotencyWindow() {
        handler.onPaymentCommitted(committedEvent("key-ttl"));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(idempotencyPort).store(eq("key-ttl"), any(PaymentResponse.class), ttl.capture());

        assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(24));
    }
}
