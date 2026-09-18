package com.sentinelpay.api.integration;

import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.dto.PaymentResponse;
import com.sentinelpay.domain.identity.Role;
import com.sentinelpay.domain.model.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** JWT authentication and authorization against the fully wired application. */
class SecurityFlowIT extends AbstractIntegrationTest {

    // Fixture values for users created inside the throwaway PostgreSQL container.
    private static final String USER_PASSWORD = "integration-test-payer-password";
    private static final String ADMIN_PASSWORD = "integration-test-admin-password";

    @Test
    void loginWithValidCredentialsReturnsUsableToken() {
        UUID accountId = insertAccount("ACC-SEC-001", "5000.00");
        insertUser("payer", USER_PASSWORD, Role.USER, accountId);

        ResponseEntity<AuthTokens> response = loginFor("payer", USER_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(response.getBody().expiresIn()).isEqualTo(900);
        assertThat(response.getBody().username()).isEqualTo("payer");
        assertThat(response.getBody().roles()).containsExactly(Role.USER);
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        UUID accountId = insertAccount("ACC-SEC-002", "5000.00");
        insertUser("payer", USER_PASSWORD, Role.USER, accountId);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                loginBody("payer", "WrongPassword1"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("/errors/invalid-credentials");
    }

    @Test
    void loginIsRejectedForDisabledAccount() {
        UUID accountId = insertAccount("ACC-SEC-003", "5000.00");
        insertUser("payer", USER_PASSWORD, Role.USER, accountId);
        jdbcTemplate.update("UPDATE users SET enabled = FALSE WHERE username = 'payer'");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                loginBody("payer", USER_PASSWORD),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void paymentWithoutTokenIsUnauthorized() {
        UUID accountId = insertAccount("ACC-SEC-004", "5000.00");

        ResponseEntity<String> response =
                postPayment(null, UUID.randomUUID().toString(), accountId, "100.00", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("/errors/unauthorized");
        assertThat(countTransactions()).isZero();
    }

    @Test
    void paymentWithTamperedTokenIsUnauthorized() {
        UUID accountId = insertAccount("ACC-SEC-005", "5000.00");
        insertUser("payer", USER_PASSWORD, Role.USER, accountId);
        String token = accessTokenFor("payer", USER_PASSWORD);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        ResponseEntity<String> response =
                postPayment(tampered, UUID.randomUUID().toString(), accountId, "100.00", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Access token is invalid");
        assertThat(countTransactions()).isZero();
    }

    @Test
    void userCannotPayFromAnotherUsersAccount() {
        UUID ownAccount = insertAccount("ACC-SEC-006", "5000.00");
        UUID foreignAccount = insertAccount("ACC-SEC-007", "5000.00");
        insertUser("payer", USER_PASSWORD, Role.USER, ownAccount);
        String token = accessTokenFor("payer", USER_PASSWORD);

        ResponseEntity<String> response =
                postPayment(token, UUID.randomUUID().toString(), foreignAccount, "100.00", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("/errors/access-denied");
        assertThat(balanceOf(foreignAccount)).isEqualByComparingTo("5000.00");
        assertThat(countTransactions()).isZero();
    }

    @Test
    void adminCanPayFromAnyAccount() {
        UUID accountId = insertAccount("ACC-SEC-008", "5000.00");
        insertUser("siteadmin", ADMIN_PASSWORD, Role.ADMIN, null);
        String token = accessTokenFor("siteadmin", ADMIN_PASSWORD);

        ResponseEntity<PaymentResponse> response =
                postPayment(token, UUID.randomUUID().toString(), accountId, "250.00", PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("4750.00");
    }

    @Test
    void actuatorMetricsRequireAdminRole() {
        UUID accountId = insertAccount("ACC-SEC-009", "5000.00");
        insertUser("payer", USER_PASSWORD, Role.USER, accountId);
        insertUser("siteadmin", ADMIN_PASSWORD, Role.ADMIN, null);

        ResponseEntity<String> anonymous = restTemplate.getForEntity("/actuator/metrics", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> asUser = getWithToken("/actuator/metrics", accessTokenFor("payer", USER_PASSWORD));
        assertThat(asUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> asAdmin =
                getWithToken("/actuator/metrics", accessTokenFor("siteadmin", ADMIN_PASSWORD));
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthEndpointStaysPublicAndReportsEveryContainerUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"UP\"")
                .contains("\"db\"")
                .contains("\"redis\"")
                .contains("\"rabbit\"");
    }

    // ---------------------------------------------------------------- helpers

    private HttpEntity<String> loginBody(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("""
                {"username":"%s","password":"%s"}""".formatted(username, password), headers);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
