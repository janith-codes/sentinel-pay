package com.sentinelpay.infrastructure.persistence.adapter;

import com.sentinelpay.application.port.out.UserRepositoryPort;
import com.sentinelpay.domain.identity.AppUser;
import com.sentinelpay.infrastructure.persistence.entity.UserJpaEntity;
import com.sentinelpay.infrastructure.persistence.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserJpaRepository repository;

    @Override
    public Optional<AppUser> findByUsername(String username) {
        return repository.findByUsername(username).map(UserJpaEntity::toDomain);
    }
}
