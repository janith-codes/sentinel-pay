package com.sentinelpay.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.api.config.SecurityConfig;
import com.sentinelpay.api.dto.LoginRequest;
import com.sentinelpay.api.dto.PaymentRequest;
import com.sentinelpay.application.port.in.AuthenticateUserUseCase;
import com.sentinelpay.application.port.in.ProcessPaymentUseCase;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.domain.exception.InsufficientBalanceException;
import com.sentinelpay.domain.exception.InvalidCredentialsException;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.Role;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the RFC 7807 error contract: every failure mode must answer with its intended status and a
 * ProblemDetail carrying a specific {@code type} and a {@code timestamp}.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class ErrorResponseContractTest {

    private static final String SECRET = "error-contract-test-secret-0123456789abcd";
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

    private String tokenFor(Set<Role> roles, UUID accountId) {
        AppUser user = new AppUser(UUID.randomUUID(), "janith", HASH, roles, accountId, true);
        return tokenService.issue(user).token();
    }

    private String bearerUser() {
        return "Bearer " + tokenFor(Set.of(Role.USER), ownAccount);
    }

    private String paymentBody(UUID accountId) throws Exception {
        return objectMapper.writeValueAsString(new PaymentRequest(accountId, new BigDecimal("500.00"), "LKR"));
    }

    // ---------------------------------------------------------------- 400

    @Test
    void malformedJsonBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"janith\", "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("/errors/malformed-request"))
                .andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedJsonBody_doesNotLeakParserInternals() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not even close}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON"));
    }

    @Test
    void emptyRequestBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/malformed-request"));
    }

    @Test
    void validationFailure_returnsBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("/errors/validation-failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors.username").value("username is required"))
                .andExpect(jsonPath("$.errors.password").value("password is required"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------------------------------------------------------------- 404

    @Test
    void unknownEndpoint_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearerUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("/errors/endpoint-not-found"))
                .andExpect(jsonPath("$.detail").value("The requested endpoint does not exist"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unknownEndpoint_withoutToken_returnsUnauthorizedRatherThanRevealingItIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 405

    @Test
    void unsupportedHttpMethod_returnsMethodNotAllowed() throws Exception {
        mockMvc.perform(get(LOGIN_URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.type").value("/errors/method-not-allowed"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------------------------------------------------------------- 401 / 403

    @Test
    void authenticationFailure_returnsUnauthorized() throws Exception {
        when(authenticateUserUseCase.authenticate(any()))
                .thenThrow(new InvalidCredentialsException("Invalid username or password"));

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("janith", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("/errors/invalid-credentials"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authorizationFailure_returnsForbidden() throws Exception {
        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearerUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(foreignAccount)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("/errors/access-denied"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------------------------------------------------------------- 422

    @Test
    void businessFailure_returnsUnprocessableEntity() throws Exception {
        when(processPaymentUseCase.process(any(), anyString()))
                .thenThrow(new InsufficientBalanceException("Insufficient balance"));

        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearerUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(ownAccount)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("/errors/insufficient-balance"))
                .andExpect(jsonPath("$.detail").value("Insufficient balance"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------------------------------------------------------------- 500

    @Test
    void unexpectedException_returnsInternalServerErrorWithoutLeakingDetail() throws Exception {
        when(processPaymentUseCase.process(any(), anyString()))
                .thenThrow(new RuntimeException("jdbc connection pool exhausted at com.zaxxer.hikari"));

        mockMvc.perform(post(PAYMENTS_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearerUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(ownAccount)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.type").value("/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
