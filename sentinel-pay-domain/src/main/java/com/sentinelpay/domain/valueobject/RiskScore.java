package com.sentinelpay.domain.valueobject;

public record RiskScore(int score, RiskLevel level, String reason) {

    public RiskScore {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Risk score must be between 0 and 100");
        }
    }

    public static RiskScore low(String reason) {
        return new RiskScore(25, RiskLevel.LOW, reason);
    }

    public static RiskScore medium(String reason) {
        return new RiskScore(60, RiskLevel.MEDIUM, reason);
    }

    public static RiskScore high(String reason) {
        return new RiskScore(85, RiskLevel.HIGH, reason);
    }

    public boolean isHighRisk() {
        return level == RiskLevel.HIGH;
    }
}