package com.sentinelpay.infrastructure.security;

import com.sentinelpay.application.dto.IssuedToken;
import com.sentinelpay.application.exception.TokenValidationException;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.AuthenticatedUser;
import com.sentinelpay.domain.identity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtTokenServiceAdapter implements TokenServicePort {

    /** HS256 requires a 256-bit key. */
    private static final int MIN_SECRET_LENGTH = 32;

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_ACCOUNT_ID = "accountId";

    private final SecretKey signingKey;
    private final JwtParser parser;
    private final Duration expiration;
    private final String issuer;

    public JwtTokenServiceAdapter(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "sentinelpay.jwt.secret must be at least " + MIN_SECRET_LENGTH
                            + " characters; configure it through the JWT_SECRET environment variable");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Objects.requireNonNullElse(properties.expiration(), Duration.ofMinutes(15));
        this.issuer = Objects.requireNonNullElse(properties.issuer(), "sentinel-pay");
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(this.issuer)
                .build();
    }

    @Override
    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();

        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLES, user.getRoles().stream().map(Role::name).toList())
                .claim(CLAIM_ACCOUNT_ID, user.getAccountId() == null ? null : user.getAccountId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, expiration.toSeconds());
    }

    @Override
    public AuthenticatedUser verify(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_USERNAME, String.class),
                    readRoles(claims),
                    readAccountId(claims)
            );
        } catch (ExpiredJwtException e) {
            throw new TokenValidationException("Access token has expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenValidationException("Access token is invalid");
        }
    }

    private Set<Role> readRoles(Claims claims) {
        List<?> raw = claims.get(CLAIM_ROLES, List.class);
        if (raw == null) {
            return Set.of();
        }
        Set<Role> roles = new LinkedHashSet<>();
        for (Object value : raw) {
            try {
                roles.add(Role.valueOf(String.valueOf(value)));
            } catch (IllegalArgumentException unknownRole) {
                // A role that no longer exists must never grant access.
            }
        }
        return roles;
    }

    private UUID readAccountId(Claims claims) {
        String value = claims.get(CLAIM_ACCOUNT_ID, String.class);
        return value == null ? null : UUID.fromString(value);
    }
}
