package com.sentinelpay.application.port.out;

import com.sentinelpay.application.dto.PaymentResponse;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyPort {
    Optional<PaymentResponse> get(String key);
    void store(String key, PaymentResponse response, Duration ttl);
}
