package com.sentinelpay.api.security;

import com.sentinelpay.application.exception.TokenValidationException;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.domain.identity.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Translates a {@code Authorization: Bearer <jwt>} header into an authenticated SecurityContext.
 * Instantiated by {@code SecurityConfig} rather than declared as a bean, so that Boot does not
 * also register it in the plain servlet filter chain.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORITY_PREFIX = "ROLE_";

    private final TokenServicePort tokenService;
    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);
        if (token == null) {
            // No credentials presented. Authorization rules decide whether that is acceptable.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedUser user = tokenService.verify(token);
            SecurityContextHolder.getContext().setAuthentication(toAuthentication(user, request));
        } catch (TokenValidationException e) {
            SecurityContextHolder.clearContext();
            log.debug("Rejected request to {}: {}", request.getRequestURI(), e.getMessage());
            authenticationEntryPoint.commence(request, response, new InsufficientAuthenticationException(e.getMessage(), e));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private UsernamePasswordAuthenticationToken toAuthentication(AuthenticatedUser user, HttpServletRequest request) {
        List<GrantedAuthority> authorities = user.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(AUTHORITY_PREFIX + role.name()))
                .toList();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
