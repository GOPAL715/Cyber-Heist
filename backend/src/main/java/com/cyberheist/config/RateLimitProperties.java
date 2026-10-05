package com.cyberheist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Rate limiting for the authentication endpoints.
 *
 * <p>Implemented as a small in-memory fixed-window counter, which is enough for
 * Phase 1. A shared store (Redis) is only required once the backend is scaled
 * horizontally.
 */
@ConfigurationProperties(prefix = "app.security.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int maxAttempts,
        Duration window
) {
    public RateLimitProperties {
        if (maxAttempts <= 0) {
            maxAttempts = 20;
        }
        window = window == null || window.isNegative() || window.isZero() ? Duration.ofMinutes(1) : window;
    }
}