package com.sentinelpay.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.application.dto.PaymentCommand;
import com.sentinelpay.application.port.out.FraudDetectionPort;
import com.sentinelpay.domain.valueobject.RiskLevel;
import com.sentinelpay.domain.valueobject.RiskScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
public class SpringAIFraudDetectionAdapter implements FraudDetectionPort {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SpringAIFraudDetectionAdapter(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public RiskScore evaluate(PaymentCommand command) {
        try {
            String prompt = buildPrompt(command);
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("AI fraud detection unavailable, using rule-based fallback: {}", e.getMessage());
            return ruleBasedFallback(command);
        }
    }

    private String buildPrompt(PaymentCommand command) {
        return String.format("""
                Analyze this payment for fraud risk and respond ONLY with valid JSON, no markdown:
                {"score": <0-100>, "level": "<LOW|MEDIUM|HIGH>", "reason": "<brief reason>"}

                Transaction details:
                - Account ID: %s
                - Amount: %s %s

                Rules:
                - score 0-30 = LOW, 31-70 = MEDIUM, 71-100 = HIGH
                - Amounts over 500000 are HIGH risk
                - Amounts 100000-500000 are MEDIUM risk
                - Amounts under 100000 are LOW risk unless other factors apply
                """,
                command.accountId(), command.amount(), command.currency());
    }

    private RiskScore parseResponse(String response) {
        try {
            String cleaned = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();
            JsonNode node = objectMapper.readTree(cleaned);
            int score = node.get("score").asInt();
            String levelStr = node.get("level").asText("LOW");
            String reason = node.get("reason").asText("AI assessment");
            RiskLevel level = RiskLevel.valueOf(levelStr.toUpperCase());
            return new RiskScore(score, level, reason);
        } catch (Exception e) {
            log.warn("Failed to parse AI fraud response: {}", response);
            return RiskScore.low("Parse fallback");
        }
    }

    private RiskScore ruleBasedFallback(PaymentCommand command) {
        BigDecimal amount = command.amount();
        BigDecimal highThreshold = new BigDecimal("500000");
        BigDecimal mediumThreshold = new BigDecimal("100000");

        if (amount.compareTo(highThreshold) > 0) {
            return RiskScore.high("Amount exceeds high-risk threshold");
        } else if (amount.compareTo(mediumThreshold) > 0) {
            return RiskScore.medium("Amount exceeds medium-risk threshold");
        } else {
            return RiskScore.low("Amount within normal range");
        }
    }
}
