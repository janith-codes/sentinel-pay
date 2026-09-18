package com.sentinelpay.application.dto;

import com.sentinelpay.domain.identity.Role;

import java.util.Set;

public record AuthTokens(
        String accessToken,
        String tokenType,
        long expiresIn,
        String username,
        Set<Role> roles
) {
}
