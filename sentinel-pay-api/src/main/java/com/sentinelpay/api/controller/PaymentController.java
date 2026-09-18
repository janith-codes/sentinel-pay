package com.sentinelpay.api.controller;

import com.sentinelpay.api.dto.PaymentRequest;
import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.domain.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ProcessPaymentUseCase processPaymentUseCase;

    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        if (!caller.canTransactOn(request.accountId())) {
            log.warn("User {} attempted to transact on account {}", caller.username(), request.accountId());
            throw new AccessDeniedException("You are not allowed to transact on this account");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
        }

        log.info("Processing payment for account {} with idempotency key: {}", request.accountId(), idempotencyKey);

        PaymentCommand command = new PaymentCommand(request.accountId(), request.amount(), request.currency());
        PaymentResponse response = processPaymentUseCase.process(command, idempotencyKey);

        HttpStatus status = switch (response.status()) {
            case COMPLETED        -> HttpStatus.CREATED;
            case REJECTED, FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default               -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(response);
    }
}
