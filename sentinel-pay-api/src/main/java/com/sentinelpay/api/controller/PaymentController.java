package com.sentinelpay.api.controller;

import com.sentinelpay.api.config.OpenApiConfig;
import com.sentinelpay.api.dto.PaymentRequest;
import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.domain.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
public class PaymentController {

    private final ProcessPaymentUseCase processPaymentUseCase;

    @PostMapping
    @Operation(
            summary = "Process payment",
            description = "Processes a payment for an account the caller is allowed to use. USER may transact only on their own account. ADMIN may transact on any account.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payment completed"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the body is missing or not valid JSON",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired access token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not allowed to transact on this account, or lacks the USER or ADMIN role",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Account does not exist",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Account is not in a state that can accept the payment",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Payment was rejected or failed, or the account balance is insufficient. A rejected or failed payment returns the payment result. Insufficient balance returns an RFC 7807 problem.",
                    content = @Content(schema = @Schema(oneOf = {PaymentResponse.class, ProblemDetail.class}))),
            @ApiResponse(responseCode = "500", description = "Unexpected transaction status, or an unexpected server error. An unexpected status returns the payment result. An unhandled error returns an RFC 7807 problem.",
                    content = @Content(schema = @Schema(oneOf = {PaymentResponse.class, ProblemDetail.class})))
    })
    public ResponseEntity<PaymentResponse> processPayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Parameter(description = "Client-supplied idempotency key. When omitted, the server generates one.")
            String idempotencyKey,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser caller) {

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
