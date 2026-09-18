package com.sentinelpay.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@ConfigurationProperties(prefix = "sentinelpay.jwt")
public record JwtProperties(
        String secret,

        @DurationUnit(ChronoUnit.MILLIS)
        Duration expiration,

        String issuer
) {
}
