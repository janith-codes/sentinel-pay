package com.sentinelpay.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Emits RFC 7807 responses for authentication failures, matching {@code GlobalExceptionHandler}.
 * Needed because failures raised inside the filter chain never reach {@code @RestControllerAdvice}.
 */
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String FALLBACK_DETAIL = "Authentication is required to access this resource";

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        String detail = authException == null || authException.getMessage() == null
                ? FALLBACK_DETAIL
                : authException.getMessage();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setType(URI.create("/errors/unauthorized"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
