package com.sentinelpay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "username is required")
        @Size(max = 100, message = "username must be at most 100 characters")
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 200, message = "password must be at most 200 characters")
        String password
) {
}
