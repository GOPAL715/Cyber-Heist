package com.cyberheist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT and CORS settings, all supplied from the environment.
 *
 * @param secret       HMAC signing key; must be at least 32 bytes for HS256
 * @param issuer       value written to, and required in, the {@code iss} claim
 * @param accessTokenExpiration  lifetime of access tokens (short by design)
 * @param refreshTokenExpiration lifetime of refresh tokens (much longer)
 * @param cors         explicit browser origins allowed to call the API
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenExpiration,
        Duration refreshTokenExpiration
) {
    public long accessTokenExpiresInSeconds() {
        return accessTokenExpiration.toSeconds();
    }

    public long refreshTokenExpiresInSeconds() {
        return refreshTokenExpiration.toSeconds();
    }
}