package com.sentinelpay.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.application.port.out.AccountRepositoryPort;
import com.sentinelpay.application.service.ProcessPaymentService;
import com.sentinelpay.domain.model.Account;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.infrastructure.messaging.config.RabbitMQConfig;
import com.sentinelpay.infrastructure.messaging.event.PaymentProcessedEvent;
import com.sentinelpay.infrastructure.persistence.entity.AccountJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.retry.annotation.AnnotationAwareRetryOperationsInterceptor;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Optimistic locking retry against real PostgreSQL.
 *
 * <p>The version check runs when the transaction flushes, so a conflict is raised by the
 * transaction proxy rather than by any call inside the use case. These tests drive that genuine
 * commit-time failure, which the unit-level policy test can only simulate.
 */
class OptimisticLockRetryIT extends AbstractIntegrationTest {

    private static final long EVENT_TIMEOUT_MS = 5_000;
    private static final long NO_EVENT_TIMEOUT_MS = 1_000;
    private static final long COORDINATION_TIMEOUT_SECONDS = 10;

    @Autowired ProcessPaymentUseCase processPaymentUseCase;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ObjectMapper objectMapper;

    @SpyBean AccountRepositoryPort accountRepository;

    /** What one attempt saw inside its own transaction, captured just before its update. */
    record Attempt(String payer, int number, long loadedVersion, BigDecimal balanceAfterDebit,
                   EntityManager entityManager) {
    }

    // ---------------------------------------------------------------- helpers

    private PaymentProcessedEvent receiveEvent(long timeoutMs) {
        return (PaymentProcessedEvent) rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUEUE, timeoutMs);
    }

    private PaymentResponse cachedResponse(String idempotencyKey) throws Exception {
        String json = stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + idempotencyKey);
        assertThat(json).as("cached response for %s", idempotencyKey).isNotNull();
        return objectMapper.readValue(json, PaymentResponse.class);
    }

    /**
     * Bumps the row version on a connection of its own, so the change commits independently of the
     * transaction in flight - exactly what a competing payment would have done.
     */
    private void bumpVersionOnAnotherConnection(UUID accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE accounts SET version = version + 1 WHERE id = ?")) {
            update.setObject(1, accountId);
            update.executeUpdate();
        }
    }

    private AtomicInteger conflictOnFirstSaveOnly(UUID accountId) {
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 1) {
                bumpVersionOnAnotherConnection(accountId);
            }
            return invocation.callRealMethod();
        }).when(accountRepository).save(any(Account.class));
        return saves;
    }

    // Coordination failures are raised as Errors: the service turns unexpected Exceptions into a
    // FAILED payment, which would hide the real cause behind a status assertion.
    private static void awaitOrFail(CyclicBarrier barrier) throws InterruptedException {
        try {
            barrier.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException | BrokenBarrierException e) {
            throw new AssertionError("both payments never reached their first update", e);
        }
    }

    private static void awaitOrFail(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(COORDINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("payment A never committed");
        }
    }

    private static Attempt attempt(List<Attempt> attempts, String payer, int number) {
        return attempts.stream()
                .filter(a -> a.payer().equals(payer) && a.number() == number)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no attempt " + number + " recorded for " + payer));
    }

    // ---------------------------------------------------------------- proxy ordering

    @Test
    void retryAdviceWrapsTheTransactionAdviceOnTheProductionBean() {
        assertThat(AopUtils.isAopProxy(processPaymentUseCase)).isTrue();

        List<Advice> chain = Arrays.stream(((Advised) processPaymentUseCase).getAdvisors())
                .map(Advisor::getAdvice)
                .toList();
        int retry = indexOf(chain, AnnotationAwareRetryOperationsInterceptor.class);
        int transaction = indexOf(chain, TransactionInterceptor.class);

        assertThat(retry).as("retry advice is applied").isNotNegative();
        assertThat(transaction).as("transaction advice is applied").isNotNegative();
        assertThat(retry)
                .as("retry must sit outside the transaction so every attempt gets a new one")
                .isLessThan(transaction);
    }

    private static int indexOf(List<Advice> chain, Class<? extends Advice> type) {
        for (int i = 0; i < chain.size(); i++) {
            if (type.isInstance(chain.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- deterministic conflict

    @Test
    void conflictOnTheFirstAttemptIsRetriedAgainstFreshStateAndDebitsExactlyOnce() {
        UUID accountId = insertAccount("ACC-LOCK-001", "1000.00");
        AtomicInteger saves = conflictOnFirstSaveOnly(accountId);

        PaymentResponse response = processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("400.00"), "LKR"), "idem-lock-001");

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(saves.get()).as("one rolled back attempt plus the one that committed").isEqualTo(2);

        // Debited once, from the balance the retry reloaded rather than the stale one.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("600.00");

        // The rolled back attempt left no transaction row behind.
        assertThat(countTransactions()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM transactions", UUID.class)).isEqualTo(response.transactionId());
    }

    @Test
    void onlyTheCommittedAttemptProducesPostCommitEffects() throws Exception {
        UUID accountId = insertAccount("ACC-LOCK-002", "1000.00");
        conflictOnFirstSaveOnly(accountId);

        PaymentResponse response = processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("250.00"), "LKR"), "idem-lock-002");

        PaymentProcessedEvent event = receiveEvent(EVENT_TIMEOUT_MS);
        assertThat(event).isNotNull();
        assertThat(event.transactionId()).isEqualTo(response.transactionId());
        assertThat(receiveEvent(NO_EVENT_TIMEOUT_MS))
                .as("the rolled back attempt must not have published its own event")
                .isNull();

        // One idempotency record, pointing at the transaction that actually committed.
        assertThat(stringRedisTemplate.keys(REDIS_KEY_PREFIX + "*")).hasSize(1);
        assertThat(cachedResponse("idem-lock-002").transactionId()).isEqualTo(response.transactionId());
    }

    @Test
    void conflictOnEveryAttemptFailsAfterTheAttemptLimitAndChangesNothing() {
        UUID accountId = insertAccount("ACC-LOCK-003", "1000.00");
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            saves.incrementAndGet();
            bumpVersionOnAnotherConnection(accountId);
            return invocation.callRealMethod();
        }).when(accountRepository).save(any(Account.class));

        assertThatThrownBy(() -> processPaymentUseCase.process(
                new PaymentCommand(accountId, new BigDecimal("400.00"), "LKR"), "idem-lock-003"))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(saves.get()).isEqualTo(ProcessPaymentService.MAX_ATTEMPTS);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
        assertThat(countTransactions()).isZero();
        assertThat(receiveEvent(NO_EVENT_TIMEOUT_MS)).isNull();
        assertThat(stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "idem-lock-003")).isNull();
    }

    // ---------------------------------------------------------------- real concurrency

    /**
     * Two payments on one account, choreographed so the race is guaranteed rather than hoped for:
     * <ol>
     *   <li>both first attempts load the account and wait at a barrier - both now hold version 0</li>
     *   <li>payment A carries on and commits, moving the row to version 1</li>
     *   <li>payment B is released only after A has committed, so its version-0 update matches no
     *       row, the flush fails, and the transaction rolls back</li>
     *   <li>B's retry opens a new transaction, reloads version 1 and commits</li>
     * </ol>
     * Neither payment holds a row lock while waiting: up to that point each has only read the row,
     * and the update is not flushed until commit. So the choreography cannot deadlock.
     */
    @Test
    void concurrentPaymentsCollideAndTheLoserRetriesAgainstTheWinnersCommit() throws Exception {
        UUID accountId = insertAccount("ACC-LOCK-004", "1000.00");

        CyclicBarrier bothHoldVersionZero = new CyclicBarrier(2);
        CountDownLatch paymentACommitted = new CountDownLatch(1);
        ThreadLocal<String> payer = new ThreadLocal<>();
        Map<String, AtomicInteger> attemptsByPayer = new ConcurrentHashMap<>();
        List<Attempt> attempts = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            String who = payer.get();
            int number = attemptsByPayer.computeIfAbsent(who, key -> new AtomicInteger()).incrementAndGet();

            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            if (entityManager == null) {
                throw new AssertionError("save() must run inside the payment transaction");
            }
            // Served from this attempt's persistence context: the version it loaded, before any flush.
            long loadedVersion = entityManager.find(AccountJpaEntity.class, accountId).getVersion();
            Account debited = invocation.getArgument(0);
            attempts.add(new Attempt(who, number, loadedVersion, debited.getBalance().amount(), entityManager));

            if (number == 1) {
                awaitOrFail(bothHoldVersionZero);
                if ("B".equals(who)) {
                    awaitOrFail(paymentACommitted);
                }
            }
            return invocation.callRealMethod();
        }).when(accountRepository).save(any(Account.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        PaymentResponse responseA;
        PaymentResponse responseB;
        try {
            Future<PaymentResponse> paymentA = pool.submit(() -> {
                payer.set("A");
                try {
                    return processPaymentUseCase.process(
                            new PaymentCommand(accountId, new BigDecimal("400.00"), "LKR"), "idem-lock-004-a");
                } finally {
                    // process() returns only after its transaction has committed.
                    paymentACommitted.countDown();
                }
            });
            Future<PaymentResponse> paymentB = pool.submit(() -> {
                payer.set("B");
                return processPaymentUseCase.process(
                        new PaymentCommand(accountId, new BigDecimal("300.00"), "LKR"), "idem-lock-004-b");
            });

            responseA = paymentA.get(30, TimeUnit.SECONDS);
            responseB = paymentB.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(responseA.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(responseB.status()).isEqualTo(TransactionStatus.COMPLETED);

        // The conflict genuinely happened: A committed first time, B needed a second attempt.
        assertThat(attemptsByPayer.get("A").get()).isEqualTo(1);
        assertThat(attemptsByPayer.get("B").get()).isEqualTo(2);

        Attempt a1 = attempt(attempts, "A", 1);
        Attempt b1 = attempt(attempts, "B", 1);
        Attempt b2 = attempt(attempts, "B", 2);

        // Both first attempts observed the same version before either committed.
        assertThat(a1.loadedVersion()).isZero();
        assertThat(b1.loadedVersion()).isZero();

        // B's first attempt worked from the stale balance; its retry from the one A committed.
        assertThat(b1.balanceAfterDebit()).isEqualByComparingTo("700.00");
        assertThat(b2.loadedVersion()).isEqualTo(1L);
        assertThat(b2.balanceAfterDebit()).isEqualByComparingTo("300.00");

        // The retry ran in a new persistence context, not the one whose flush failed.
        assertThat(b2.entityManager()).isNotSameAs(b1.entityManager());

        // Each payment debited exactly once: two successful version increments, nothing lost.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("300.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM accounts WHERE id = ?", Long.class, accountId)).isEqualTo(2L);

        // No row survives from B's rolled back attempt, and neither key was duplicated.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, idempotency_key FROM transactions ORDER BY idempotency_key");
        assertThat(rows).extracting(row -> row.get("idempotency_key"))
                .containsExactly("idem-lock-004-a", "idem-lock-004-b");
        assertThat(rows).extracting(row -> row.get("id"))
                .containsExactly(responseA.transactionId(), responseB.transactionId());

        // One event per committed payment - B's rolled back attempt published nothing.
        List<UUID> published = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            PaymentProcessedEvent event = receiveEvent(EVENT_TIMEOUT_MS);
            assertThat(event).as("event %d of 2", i + 1).isNotNull();
            published.add(event.transactionId());
        }
        assertThat(published).containsExactlyInAnyOrder(responseA.transactionId(), responseB.transactionId());
        assertThat(receiveEvent(NO_EVENT_TIMEOUT_MS)).as("no third event").isNull();

        // One idempotency record per key, each pointing at its committed transaction.
        assertThat(stringRedisTemplate.keys(REDIS_KEY_PREFIX + "*")).hasSize(2);
        assertThat(cachedResponse("idem-lock-004-a").transactionId()).isEqualTo(responseA.transactionId());
        assertThat(cachedResponse("idem-lock-004-b").transactionId()).isEqualTo(responseB.transactionId());
    }
}
