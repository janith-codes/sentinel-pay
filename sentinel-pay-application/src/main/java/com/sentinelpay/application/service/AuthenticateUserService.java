package com.sentinelpay.application.service;

import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.dto.IssuedToken;
import com.sentinelpay.application.dto.LoginCommand;
import com.sentinelpay.application.port.in.AuthenticateUserUseCase;
import com.sentinelpay.application.port.out.PasswordHasherPort;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.application.port.out.UserRepositoryPort;
import com.sentinelpay.domain.exception.InvalidCredentialsException;
import com.sentinelpay.domain.identity.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticateUserService implements AuthenticateUserUseCase {

    /** Identical for unknown users, wrong passwords and disabled accounts, to prevent username enumeration. */
    private static final String GENERIC_FAILURE = "Invalid username or password";

    private static final String BEARER = "Bearer";

    private final UserRepositoryPort userRepository;
    private final PasswordHasherPort passwordHasher;
    private final TokenServicePort tokenService;

    @Override
    @Transactional(readOnly = true)
    public AuthTokens authenticate(LoginCommand command) {
        AppUser user = userRepository.findByUsername(command.username())
                .orElseThrow(() -> {
                    log.warn("Login failed - unknown username: {}", command.username());
                    return new InvalidCredentialsException(GENERIC_FAILURE);
                });

        if (!user.isEnabled()) {
            log.warn("Login failed - account disabled: {}", user.getUsername());
            throw new InvalidCredentialsException(GENERIC_FAILURE);
        }

        if (!passwordHasher.matches(command.password(), user.getPasswordHash())) {
            log.warn("Login failed - bad password for username: {}", user.getUsername());
            throw new InvalidCredentialsException(GENERIC_FAILURE);
        }

        IssuedToken issued = tokenService.issue(user);
        log.info("User {} authenticated successfully", user.getUsername());

        return new AuthTokens(
                issued.token(),
                BEARER,
                issued.expiresInSeconds(),
                user.getUsername(),
                user.getRoles()
        );
    }
}
