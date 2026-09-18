package com.sentinelpay.api.controller;

import com.sentinelpay.api.dto.LoginRequest;
import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.dto.LoginCommand;
import com.sentinelpay.application.port.in.AuthenticateUserUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticateUserUseCase authenticateUserUseCase;

    @PostMapping("/login")
    public ResponseEntity<AuthTokens> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for username: {}", request.username());

        AuthTokens tokens = authenticateUserUseCase.authenticate(
                new LoginCommand(request.username(), request.password()));

        return ResponseEntity.ok(tokens);
    }
}
