package com.sentinelpay.application.port.out;

import com.sentinelpay.application.dto.IssuedToken;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.domain.identity.AuthenticatedUser;

public interface TokenServicePort {

    IssuedToken issue(AppUser user);

    /**
     * @throws com.sentinelpay.application.exception.TokenValidationException if the token is expired or invalid
     */
    AuthenticatedUser verify(String token);
}
