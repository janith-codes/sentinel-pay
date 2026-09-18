package com.sentinelpay.infrastructure.persistence.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Explicit JPA wiring: required because Spring Data JPA and Spring Data Redis are both on the
 * classpath, which puts repository detection into strict mode.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.sentinelpay.infrastructure.persistence.repository")
@EntityScan(basePackages = "com.sentinelpay.infrastructure.persistence.entity")
public class JpaConfig {
}
