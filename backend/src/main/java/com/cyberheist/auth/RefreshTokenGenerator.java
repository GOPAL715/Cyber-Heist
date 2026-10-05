package com.cyberheist.auth;

import com.cyberheist.config.JwtProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates refresh tokens and derives the hashes that are stored.
 *
 * <p>Clients receive 256 bits of cryptographically random data in Base64URL
 * form. Only the SHA-256 digest is persisted, so database contents cannot be
 * replayed against the API. SHA-256 is appropriate here because the input is
 * full-entropy random data rather than a guessable password; password hashing
 * uses BCrypt instead.
 */
@Component
public class RefreshTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();
    private final JwtProperties properties;

    public RefreshTokenGenerator(JwtProperties properties) {
        this.properties = properties;
    }

    /** Issues a fresh opaque token and its expiry instant. */
    public GeneratedRefreshToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedRefreshToken(rawToken, hash(rawToken));
    }

    /** SHA-256 hex digest of the raw token. */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the platform; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public long expiresInSeconds() {
        return properties.refreshTokenExpiresInSeconds();
    }

    /**
     * A newly minted refresh token.
     *
     * @param rawToken the value sent to the client; never persisted
     * @param hash     the value stored in {@code refresh_tokens.token_hash}
     */
    public record GeneratedRefreshToken(String rawToken, String hash) {
    }
}