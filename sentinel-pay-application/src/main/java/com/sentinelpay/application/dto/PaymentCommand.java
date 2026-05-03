package com.sentinelpay.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCommand(UUID accountId, BigDecimal amount, String currency) {
}
