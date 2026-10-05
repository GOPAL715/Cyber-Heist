package com.cyberheist.security;

import com.cyberheist.config.JwtProperties;
import com.cyberheist.user.Role;
import com.cyberheist.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for token creation and validation. */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-value-long-enough-for-hmac-sha256-signing";
    private static final String ISSUER = "cyber-heist-test";

    private JwtTokenProvider provider(Duration accessTtl) {
        return new JwtTokenProvider(new JwtProperties(SECRET, ISSUER, accessTtl, Duration.ofDays(7)));
    }

    private User sampleUser() {
        return new User(UUID.randomUUID(), "shadow", "shadow@example.com", "hash", Role.PLAYER);
    }

    @Test
    @DisplayName("round-trips a valid access token to the original principal")
    void createsAndResolvesToken() {
        User user = sampleUser();
        String token = provider(Duration.ofMinutes(15)).createAccessToken(user);

        AuthenticatedPrincipal principal = provider(Duration.ofMinutes(15)).resolvePrincipal(token);

        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo(user.getId());
        assertThat(principal.role()).isEqualTo(Role.PLAYER);
    }

    @Test
    @DisplayName("returns null for a token that is already expired")
    void rejectsExpiredToken() {
        User user = sampleUser();
        // A negative lifetime makes the token expire the instant it is created.
        String token = provider(Duration.ofSeconds(-10)).createAccessToken(user);

        assertThat(provider(Duration.ofMinutes(15)).resolvePrincipal(token)).isNull();
    }

    @Test
    @DisplayName("returns null for a malformed or empty token")
    void rejectsMalformedToken() {
        JwtTokenProvider subject = provider(Duration.ofMinutes(15));

        assertThat(subject.resolvePrincipal("not.a.jwt")).isNull();
        assertThat(subject.resolvePrincipal("")).isNull();
        assertThat(subject.resolvePrincipal(null)).isNull();
    }

    @Test
    @DisplayName("returns null when the token was signed with a different secret")
    void rejectsForeignSignature() {
        User user = sampleUser();
        String token = provider(Duration.ofMinutes(15)).createAccessToken(user);

        JwtTokenProvider other = new JwtTokenProvider(new JwtProperties(
                "a-completely-different-secret-also-long-enough-here", ISSUER,
                Duration.ofMinutes(15), Duration.ofDays(7)));

        assertThat(other.resolvePrincipal(token)).isNull();
    }

    @Test
    @DisplayName("refuses to start when the secret is too short for HS256")
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(
                new JwtProperties("too-short", ISSUER, Duration.ofMinutes(15), Duration.ofDays(7))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("writes only the intended claims and no personal data")
    void tokenContainsMinimalClaims() {
        User user = sampleUser();
        String token = provider(Duration.ofMinutes(15)).createAccessToken(user);

        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));

        assertThat(payload)
                .contains("\"sub\"", "\"role\"", "\"iat\"", "\"exp\"")
                // Email and username must not be readable from the token itself.
                .doesNotContain("shadow@example.com")
                .doesNotContain("\"username\"")
                .doesNotContain("\"email\"");
    }
}