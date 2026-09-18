package com.sentinelpay.infrastructure.security;

import com.sentinelpay.application.exception.TokenValidationException;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.AuthenticatedUser;
import com.sentinelpay.domain.identity.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceAdapterTest {

    private static final String SECRET = "unit-test-secret-key-0123456789abcdefghij";
    private static final String OTHER_SECRET = "another-secret-key-9876543210zyxwvutsrqp";
    private static final String ISSUER = "sentinel-pay";
    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuv";

    private final JwtTokenServiceAdapter tokenService =
            new JwtTokenServiceAdapter(new JwtProperties(SECRET, Duration.ofMinutes(15), ISSUER));

    private AppUser user(Set<Role> roles, UUID accountId) {
        return new AppUser(UUID.randomUUID(), "janith", HASH, roles, accountId, true);
    }

    @Test
    void issueThenVerify_roundTripsIdentity() {
        UUID accountId = UUID.randomUUID();
        AppUser user = user(Set.of(Role.USER, Role.ADMIN), accountId);

        String token = tokenService.issue(user).token();
        AuthenticatedUser verified = tokenService.verify(token);

        assertThat(verified.userId()).isEqualTo(user.getId());
        assertThat(verified.username()).isEqualTo("janith");
        assertThat(verified.accountId()).isEqualTo(accountId);
        assertThat(verified.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    void issue_reportsExpiryInSeconds() {
        assertThat(tokenService.issue(user(Set.of(Role.USER), null)).expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void verify_returnsNullAccountIdWhenUserHasNoLinkedAccount() {
        String token = tokenService.issue(user(Set.of(Role.USER), null)).token();

        assertThat(tokenService.verify(token).accountId()).isNull();
    }

    @Test
    void verify_rejectsExpiredToken() {
        JwtTokenServiceAdapter expiredIssuer =
                new JwtTokenServiceAdapter(new JwtProperties(SECRET, Duration.ofSeconds(-60), ISSUER));
        String expired = expiredIssuer.issue(user(Set.of(Role.USER), null)).token();

        assertThatThrownBy(() -> tokenService.verify(expired))
                .isInstanceOf(TokenValidationException.class)
                .hasMessage("Access token has expired");
    }

    @Test
    void verify_rejectsTamperedSignature() {
        String token = tokenService.issue(user(Set.of(Role.USER), null)).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThatThrownBy(() -> tokenService.verify(tampered))
                .isInstanceOf(TokenValidationException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void verify_rejectsTokenSignedWithDifferentSecret() {
        JwtTokenServiceAdapter attacker =
                new JwtTokenServiceAdapter(new JwtProperties(OTHER_SECRET, Duration.ofMinutes(15), ISSUER));
        String forged = attacker.issue(user(Set.of(Role.ADMIN), null)).token();

        assertThatThrownBy(() -> tokenService.verify(forged))
                .isInstanceOf(TokenValidationException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void verify_rejectsTokenFromUnexpectedIssuer() {
        JwtTokenServiceAdapter otherIssuer =
                new JwtTokenServiceAdapter(new JwtProperties(SECRET, Duration.ofMinutes(15), "some-other-service"));
        String token = otherIssuer.issue(user(Set.of(Role.USER), null)).token();

        assertThatThrownBy(() -> tokenService.verify(token))
                .isInstanceOf(TokenValidationException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void verify_rejectsMalformedToken() {
        assertThatThrownBy(() -> tokenService.verify("not-a-jwt"))
                .isInstanceOf(TokenValidationException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void constructor_rejectsSecretShorterThanRequiredKeyLength() {
        JwtProperties weak = new JwtProperties("too-short", Duration.ofMinutes(15), ISSUER);

        assertThatThrownBy(() -> new JwtTokenServiceAdapter(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void constructor_rejectsMissingSecret() {
        JwtProperties missing = new JwtProperties(null, Duration.ofMinutes(15), ISSUER);

        assertThatThrownBy(() -> new JwtTokenServiceAdapter(missing))
                .isInstanceOf(IllegalStateException.class);
    }
}
