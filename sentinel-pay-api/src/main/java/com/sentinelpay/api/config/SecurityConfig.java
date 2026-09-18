package com.sentinelpay.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.api.security.JwtAuthenticationFilter;
import com.sentinelpay.api.security.ProblemDetailAccessDeniedHandler;
import com.sentinelpay.api.security.ProblemDetailAuthenticationEntryPoint;
import com.sentinelpay.application.port.out.TokenServicePort;
import com.sentinelpay.domain.identity.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    ProblemDetailAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemDetailAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    ProblemDetailAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return new ProblemDetailAccessDeniedHandler(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenServicePort tokenService,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                // No cookies or server-side sessions are used, so there is no CSRF vector to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                        .hasAnyRole(Role.USER.name(), Role.ADMIN.name())
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokenService, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
