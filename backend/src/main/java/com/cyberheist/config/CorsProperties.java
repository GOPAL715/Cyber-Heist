package com.cyberheist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Explicit CORS configuration.
 *
 * <p>Origins are never defaulted to {@code *} - {@code allowCredentials} is
 * incompatible with a wildcard origin anyway.
 */
@ConfigurationProperties(prefix = "app.security.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        boolean allowCredentials,
        Duration maxAge
) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}