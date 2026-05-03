package com.sentinelpay.application.port.in;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;

public interface ProcessPaymentUseCase {
    PaymentResponse process(PaymentCommand command, String idempotencyKey);
}