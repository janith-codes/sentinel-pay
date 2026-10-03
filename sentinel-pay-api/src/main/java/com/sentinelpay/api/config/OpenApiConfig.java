package com.sentinelpay.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * API metadata already recorded on the parent POM. No server URL is declared, so Swagger uses the
 * host that served the document.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "SentinelPay API",
                description = "REST API for the SentinelPay payment platform.",
                version = "1.0.0-SNAPSHOT",
                contact = @Contact(
                        name = "Janith Sandaruwan",
                        email = "janithsandaruwan.dev@gmail.com"),
                license = @License(
                        name = "Apache License, Version 2.0",
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt")))
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT access token returned by login. Send it as Authorization: Bearer <token>.")
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
