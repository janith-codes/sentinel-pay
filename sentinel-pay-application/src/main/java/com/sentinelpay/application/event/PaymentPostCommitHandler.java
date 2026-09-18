package com.sentinelpay.application.event;

import com.sentinelpay.application.port.out.EventPublisherPort;
import com.sentinelpay.application.port.out.IdempotencyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;

/**
 * Performs the payment side effects that must not be visible before the payment is durable.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} runs on the caller's thread once the database
 * transaction has committed, and is skipped entirely on rollback. It also runs before the use case
 * returns to its caller, so a duplicate request cannot overtake the cached response.
 *
 * <p>Neither port may throw from here: the payment is already committed, so a failure would report
 * an error for money that has moved. Both adapters absorb their own infrastructure failures.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentPostCommitHandler {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final EventPublisherPort eventPublisher;
    private final IdempotencyPort idempotencyPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCommitted(PaymentCommittedEvent event) {
        log.debug("Publishing post-commit effects for tx {}", event.transaction().getId());

        eventPublisher.publishPaymentProcessed(event.transaction());
        idempotencyPort.store(event.idempotencyKey(), event.response(), IDEMPOTENCY_TTL);
    }
}
