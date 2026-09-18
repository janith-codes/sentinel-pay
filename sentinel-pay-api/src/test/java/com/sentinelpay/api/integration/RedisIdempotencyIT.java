package com.sentinelpay.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.out.IdempotencyPort;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.infrastructure.redis.RedisIdempotencyAdapter;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Idempotency storage against a real Redis container. */
class RedisIdempotencyIT extends AbstractIntegrationTest {

    @Autowired IdempotencyPort idempotencyPort;
    @Autowired ObjectMapper objectMapper;

    private PaymentResponse sampleResponse() {
        return new PaymentResponse(UUID.randomUUID(), TransactionStatus.COMPLETED,
                "Payment processed successfully");
    }

    @Test
    void storedResponseRoundTripsThroughRedis() {
        PaymentResponse stored = sampleResponse();

        idempotencyPort.store("round-trip-key", stored, Duration.ofMinutes(5));
        Optional<PaymentResponse> loaded = idempotencyPort.get("round-trip-key");

        assertThat(loaded).contains(stored);
    }

    @Test
    void missingKeyResolvesToEmpty() {
        assertThat(idempotencyPort.get("never-stored-key")).isEmpty();
    }

    @Test
    void storedValueIsJsonUnderThePrefixedKey() throws Exception {
        PaymentResponse stored = sampleResponse();

        idempotencyPort.store("serialization-key", stored, Duration.ofMinutes(5));

        String raw = stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + "serialization-key");
        assertThat(raw).isNotNull();
        assertThat(objectMapper.readValue(raw, PaymentResponse.class)).isEqualTo(stored);
        assertThat(objectMapper.readTree(raw).get("status").asText())
                .isEqualTo(TransactionStatus.COMPLETED.name());
    }

    @Test
    void storeAppliesTheRequestedTtl() {
        idempotencyPort.store("ttl-key", sampleResponse(), Duration.ofMinutes(10));

        Long ttlSeconds = stringRedisTemplate.getExpire(REDIS_KEY_PREFIX + "ttl-key", TimeUnit.SECONDS);

        assertThat(ttlSeconds).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(600L);
    }

    @Test
    void keyDisappearsOnceTheTtlElapses() {
        idempotencyPort.store("expiring-key", sampleResponse(), Duration.ofSeconds(1));
        assertThat(idempotencyPort.get("expiring-key")).isPresent();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(idempotencyPort.get("expiring-key")).isEmpty());
    }

    /**
     * A Redis outage must never reach the caller: lookups degrade to misses and stores are dropped,
     * because by the time a store runs the payment has already committed. A throwaway connection
     * factory on a closed port is used so the shared container stays healthy for the other tests.
     */
    @Test
    void unavailableRedisDegradesToMissesAndDroppedStoresWithoutThrowing() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", closedPort),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(500))
                        .shutdownTimeout(Duration.ZERO)
                        .build());
        deadFactory.afterPropertiesSet();

        try {
            StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
            deadTemplate.afterPropertiesSet();
            RedisIdempotencyAdapter adapter = new RedisIdempotencyAdapter(deadTemplate, objectMapper);

            assertThat(adapter.get("any-key")).isEmpty();
            assertThatCode(() -> adapter.store("any-key", sampleResponse(), Duration.ofMinutes(1)))
                    .doesNotThrowAnyException();
        } finally {
            deadFactory.destroy();
        }
    }
}
