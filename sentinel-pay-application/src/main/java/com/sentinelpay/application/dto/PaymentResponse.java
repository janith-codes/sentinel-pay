package com.sentinelpay.application.dto;

import com.sentinelpay.domain.model.TransactionStatus;

import java.util.UUID;

public record PaymentResponse(UUID transactionId, TransactionStatus status, String message) {
}
