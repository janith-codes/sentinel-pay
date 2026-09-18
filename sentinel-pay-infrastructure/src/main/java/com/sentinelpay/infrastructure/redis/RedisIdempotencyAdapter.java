package com.sentinelpay.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.out.IdempotencyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<PaymentResponse> get(String key) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (value == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(value, PaymentResponse.class));
        } catch (Exception e) {
            log.warn("Failed to read idempotency key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Runs after the payment has committed, so a cache failure must never reach the caller.
     * Losing the entry only degrades duplicate detection to the unique constraint on
     * {@code transactions.idempotency_key}.
     */
    @Override
    public void store(String key, PaymentResponse response, Duration ttl) {
        try {
            String value = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttl);
        } catch (Exception e) {
            log.warn("Failed to store idempotency key {}: {}", key, e.getMessage());
        }
    }
}
