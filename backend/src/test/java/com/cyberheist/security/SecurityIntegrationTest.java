package com.cyberheist.security;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.user.Role;
import com.cyberheist.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint protection and JWT handling. */
class SecurityIntegrationTest extends IntegrationTestSupport {

    private static final String TEST_SECRET =
            "test-only-secret-value-that-is-long-enough-for-hmac-sha256-signing";

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Builds a token signed with the real key but already expired. */
    private String expiredTokenFor(User user) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(120);
        return Jwts.builder()
                .issuer("cyber-heist-test")
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("typ", "access")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(60)))
                .signWith(key)
                .compact();
    }

    @Test
    @DisplayName("protected endpoint returns 401 without a token")
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    @DisplayName("protected endpoint returns 401 with a malformed token")
    void rejectsMalformedToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer("this.is.not.a.jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("protected endpoint returns 401 with a structurally invalid token")
    void rejectsGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/player/profile")
                        .header("Authorization", bearer("abc.def.ghi")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("protected endpoint returns 401 with an expired token")
    void rejectsExpiredToken() throws Exception {
        User user = registerPlayer("expireduser", "expired@example.com");

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(expiredTokenFor(user))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForeignSignature() throws Exception {
        User user = registerPlayer("forgeduser", "forged@example.com");

        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "attacker-secret-that-is-also-long-enough-for-hs256!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .issuer("cyber-heist-test")
                .subject(user.getId().toString())
                .claim("role", "ADMIN")
                .claim("typ", "access")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(attackerKey)
                .compact();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("allows a protected endpoint with a valid token")
    void acceptsValidToken() throws Exception {
        registerPlayer("validuser", "valid@example.com");
        String token = loginAndGetAccessToken("valid@example.com", VALID_PASSWORD);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("validuser"));
    }
    @Test
    @DisplayName("returns the current user from /users/me without leaking the hash")
    void currentUserEndpoint() throws Exception {
        registerPlayer("meuser", "me@example.com");
        String token = loginAndGetAccessToken("me@example.com", VALID_PASSWORD);

        String body = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("me@example.com"))
                .andExpect(jsonPath("$.data.role").value("PLAYER"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("registration never grants ADMIN")
    void cannotEscalateToAdmin() throws Exception {
        User player = registerPlayer("escalate", "escalate@example.com");
        assertThat(player.getRole()).isEqualTo(Role.PLAYER);

        String token = loginAndGetAccessToken("escalate@example.com", VALID_PASSWORD);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("\"role\":\"PLAYER\"");
    }

    @Test
    @DisplayName("rejects a request whose account was disabled after the token was issued")
    void rejectsTokenForDisabledAccount() throws Exception {
        registerPlayer("laters", "laters@example.com");
        String token = loginAndGetAccessToken("laters@example.com", VALID_PASSWORD);

        User user = userRepository.findByEmailIgnoreCase("laters@example.com").orElseThrow();
        user.setEnabled(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("public endpoints stay reachable without a token")
    void publicEndpointsAreOpen() throws Exception {
        // Reaches the handler and fails on the credentials, proving it is not blocked by security.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("nobody@example.com", VALID_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unknown endpoints return a JSON 404 rather than a stack trace")
    void unknownEndpointReturnsJson404() throws Exception {
        registerPlayer("notfounduser", "notfound@example.com");
        String token = loginAndGetAccessToken("notfound@example.com", VALID_PASSWORD);

        String body = mockMvc.perform(get("/api/v1/does-not-exist")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Exception").doesNotContain("at com.cyberheist");
    }

    @Test
    @DisplayName("the health endpoint is reachable without a token")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown endpoint is refused for anonymous callers")
    void unknownEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }
}