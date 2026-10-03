package com.sentinelpay.application.port.out;

import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.domain.valueobject.RiskScore;

public interface FraudDetectionPort {
    RiskScore evaluate(PaymentCommand command);
}
