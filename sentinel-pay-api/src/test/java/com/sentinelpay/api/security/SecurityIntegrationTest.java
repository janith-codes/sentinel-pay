package com.sentinelpay.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.api.config.SecurityConfig;
import com.sentinelpay.api.dto.LoginRequest;
import com.sentinelpay.api.dto.PaymentRequest;
import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.application.port.in.AuthenticateUserUseCase;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.domain.exception.InvalidCredentialsException;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.Role;
import com.sentinelpay.domain.model.TransactionStatus;
import com.sentinelpay.infrastructure.security.JwtProperties;
import com.sentinelpay.infrastructure.security.JwtTokenServiceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real security filter chain end to end at the web layer.
 * Use cases are mocked so that no database, cache or broker is required.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityIntegrationTest {

    private static final String SECRET = "integration-test-secret-0123456789abcdefgh";
    private static final String ISSUER = "sentinel-pay";
    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuv";
    private static final String PAYMENTS_URL = "/api/v1/payments";
    private static final String LOGIN_URL = "/api/v1/auth/login";

    @TestConfiguration
    static class JwtTestConfig {
        @Bean
        TokenServicePort tokenServicePort() {
            return new JwtTokenServiceAdapter(new JwtProperties(SECRET, Duration.ofMinutes(15), ISSUER));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TokenServicePort tokenService;

    @MockBean AuthenticateUserUseCase authenticateUserUseCase;
    @MockBean ProcessPaymentUseCase processPaymentUseCase;

    private final UUID ownAccount = UUID.randomUUID();
    private final UUID foreignAccount = UUID.randomUUID();

    // ---------------------------------------------------------------- helpers

    private String tokenFor(Set<Role> roles, UUID accountId) {
        AppUser user = new AppUser(UUID.randomUUID(), "janith", HASH, roles, accountId, true);
        return tokenService.issue(user).token();
    }

    private String expiredTokenFor(UUID accountId) {
        TokenServicePort expiredIssuer =
                new JwtTokenServiceAdapter(new JwtProperties(SECRET, Duration.ofSeconds(-60), ISSUER));
        AppUser user = new AppUser(UUID.randomUUID(), "janith", HASH, Set.of(Role.USER), accountId, true);
        return expiredIssuer.issue(user).token();
    }

    private String paymentBody(UUID accountId) throws Exception {
        return objectMapper.writeValueAsString(
                new PaymentRequest(accountId, new BigDecimal("500.00"), "LKR"));
    }

    private void stubSuccessfulPayment() {
        when(processPaymentUseCase.process(any(), anyString())).thenReturn(
                new PaymentResponse(UUID.randomUUID(), TransactionStatus.COMPLETED, "Payment processed successfully"));
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_withValidCredentials_returnsAccessToken() throws Exception {
        when(authenticateUserUseCase.authenticate(any())).thenReturn(
                new AuthTokens("issued-jwt", "Bearer", 900, "janith", Set.of(Role.USER)));

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("janith", "correct-password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("issued-jwt"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.username").value("janith"));
    }

    @Test
    void login_withInvalidCredentials_returnsUnauthorizedProblemDetail() throws Exception {
        when(authenticateUserUseCase.authenticate(any()))
                .thenThrow(new InvalidCredentialsException("Invalid username or password"));

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("janith", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("/errors/invalid-credentials"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    @Test
    void login_withBlankUsername_returnsBadRequest() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", "some-password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/validation-failed"));

        verify(authenticateUserUseCase, never()).authenticate(any());
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void payment_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post(PAYMENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(ownAccount)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/unauthorized"));

        verify(processPaymentUseCase, never()).process(any(), anyString());
    }

    @Test
    void payment_withMalformedToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(ownAccount)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access token is invalid"));

        verify(processPaymentUseCase, never()).process(any(), anyString());
    }

    @Test
    void payment_withExpiredToken_returnsUnauthorizedExplainingExpiry() throws Exception {
        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredTokenFor(ownAccount))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(ownAccount)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access token has expired"));

        verify(processPaymentUseCase, never()).process(any(), anyString());
    }

    @Test
    void payment_withValidUserTokenOnOwnAccount_returnsCreated() throws Exception {
        stubSuccessfulPayment();

        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Set.of(Role.USER), ownAccount))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(ownAccount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void payment_withUserTokenOnForeignAccount_returnsForbidden() throws Exception {
        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Set.of(Role.USER), ownAccount))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(foreignAccount)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/access-denied"));

        verify(processPaymentUseCase, never()).process(any(), anyString());
    }

    @Test
    void payment_withAdminTokenOnForeignAccount_returnsCreated() throws Exception {
        stubSuccessfulPayment();

        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Set.of(Role.ADMIN), null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(foreignAccount)))
                .andExpect(status().isCreated());
    }

    @Test
    void adminOnlyEndpoint_withUserToken_returnsForbidden() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Set.of(Role.USER), ownAccount)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/access-denied"));
    }

    @Test
    void adminOnlyEndpoint_withAdminToken_passesAuthorization() throws Exception {
        // Actuator endpoints are not mapped in the web slice, so only the absence of an
        // authentication/authorization rejection can be asserted here.
        int status = mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(Set.of(Role.ADMIN), null)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void publicHealthEndpoint_isReachableWithoutToken() throws Exception {
        int status = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }
}
