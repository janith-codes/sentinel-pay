package com.sentinelpay.application.service;

import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.dto.IssuedToken;
import com.sentinelpay.application.dto.LoginCommand;
import com.sentinelpay.application.port.out.PasswordHasherPort;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.application.port.out.UserRepositoryPort;
import com.sentinelpay.domain.exception.InvalidCredentialsException;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticateUserServiceTest {

    private static final String HASH = "$2a$12$storedhashvalue";
    private static final String GENERIC_FAILURE = "Invalid username or password";

    @Mock UserRepositoryPort userRepository;
    @Mock PasswordHasherPort passwordHasher;
    @Mock TokenServicePort tokenService;

    @InjectMocks
    AuthenticateUserService service;

    private AppUser enabledUser(Set<Role> roles) {
        return new AppUser(UUID.randomUUID(), "janith", HASH, roles, UUID.randomUUID(), true);
    }

    @Test
    void authenticate_returnsTokenAndMetadataForValidCredentials() {
        AppUser user = enabledUser(Set.of(Role.USER));
        when(userRepository.findByUsername("janith")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("correct-password", HASH)).thenReturn(true);
        when(tokenService.issue(user)).thenReturn(new IssuedToken("jwt-token", 900));

        AuthTokens tokens = service.authenticate(new LoginCommand("janith", "correct-password"));

        assertThat(tokens.accessToken()).isEqualTo("jwt-token");
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(900);
        assertThat(tokens.username()).isEqualTo("janith");
        assertThat(tokens.roles()).containsExactly(Role.USER);
    }

    @Test
    void authenticate_throwsGenericFailureForUnknownUsername() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(new LoginCommand("ghost", "any-password")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(GENERIC_FAILURE);

        verifyNoInteractions(tokenService);
    }

    @Test
    void authenticate_throwsGenericFailureForWrongPassword() {
        AppUser user = enabledUser(Set.of(Role.USER));
        when(userRepository.findByUsername("janith")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong-password", HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.authenticate(new LoginCommand("janith", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(GENERIC_FAILURE);

        verify(tokenService, never()).issue(any());
    }

    @Test
    void authenticate_throwsGenericFailureForDisabledAccount() {
        AppUser disabled = new AppUser(UUID.randomUUID(), "janith", HASH, Set.of(Role.USER), null, false);
        when(userRepository.findByUsername("janith")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.authenticate(new LoginCommand("janith", "correct-password")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(GENERIC_FAILURE);

        // A disabled account must not even reach password verification.
        verifyNoInteractions(passwordHasher, tokenService);
    }

    @Test
    void authenticate_issuesTokenForAdminWithAllRoles() {
        AppUser admin = enabledUser(Set.of(Role.USER, Role.ADMIN));
        when(userRepository.findByUsername("janith")).thenReturn(Optional.of(admin));
        when(passwordHasher.matches("correct-password", HASH)).thenReturn(true);
        when(tokenService.issue(admin)).thenReturn(new IssuedToken("admin-token", 900));

        AuthTokens tokens = service.authenticate(new LoginCommand("janith", "correct-password"));

        assertThat(tokens.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        verify(tokenService).issue(admin);
    }
}
