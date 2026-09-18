package com.sentinelpay.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptPasswordHasherAdapterTest {

    private final BCryptPasswordHasherAdapter hasher = new BCryptPasswordHasherAdapter();

    @Test
    void hash_neverReturnsThePlainTextPassword() {
        String hashed = hasher.hash("correct-password");

        assertThat(hashed).isNotEqualTo("correct-password").startsWith("$2a$12$");
    }

    @Test
    void hash_producesDifferentHashesForTheSamePasswordDueToSalting() {
        assertThat(hasher.hash("correct-password")).isNotEqualTo(hasher.hash("correct-password"));
    }

    @Test
    void matches_acceptsCorrectPassword() {
        assertThat(hasher.matches("correct-password", hasher.hash("correct-password"))).isTrue();
    }

    @Test
    void matches_rejectsWrongPassword() {
        assertThat(hasher.matches("wrong-password", hasher.hash("correct-password"))).isFalse();
    }

    @Test
    void matches_rejectsNullInputsInsteadOfThrowing() {
        assertThat(hasher.matches(null, hasher.hash("correct-password"))).isFalse();
        assertThat(hasher.matches("correct-password", null)).isFalse();
    }
}
