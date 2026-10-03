package com.sentinelpay.application.port.out;

import com.sentinelpay.domain.identity.AppUser;

import java.util.Optional;

public interface UserRepositoryPort {
    Optional<AppUser> findByUsername(String username);
}
