package com.cyberheist.auth;

import com.cyberheist.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Refresh token rotation and logout revocation. */
class RefreshLogoutIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenGenerator refreshTokenGenerator;

    /** Performs a refresh and returns the parsed response body. */
    private JsonNode refresh(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("exchanges a refresh token for a new access token")
    void refreshIssuesNewAccessToken() throws Exception {
        registerPlayer("refresher", "refresh@example.com");
        String refreshToken = loginAndGetBody("refresh@example.com", VALID_PASSWORD)
                .at("/data/refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value("refresher"));
    }
    @Test
    @DisplayName("rotates the refresh token: the old one cannot be reused")
    void refreshRotatesToken() throws Exception {
        registerPlayer("rotator", "rotate@example.com");
        String original = loginAndGetBody("rotate@example.com", VALID_PASSWORD)
                .at("/data/refreshToken").asText();

        String rotated = refresh(original).at("/data/refreshToken").asText();
        assertThat(rotated).isNotEqualTo(original);

        // Replaying the consumed token must now fail.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(original))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("stores only the hash of the refresh token, never the raw value")
    void storesOnlyTheHash() throws Exception {
        registerPlayer("hashstore", "hashstore@example.com");
        String rawToken = loginAndGetBody("hashstore@example.com", VALID_PASSWORD)
                .at("/data/refreshToken").asText();

        assertThat(refreshTokenRepository.findByTokenHash(rawToken)).isEmpty();
        assertThat(refreshTokenRepository.findAll())
                .isNotEmpty()
                .allSatisfy(stored -> assertThat(stored.getTokenHash())
                        .isNotEqualTo(rawToken)
                        .hasSize(64));
    }

    @Test
    @DisplayName("logout revokes the refresh token so it cannot be used again")
    void logoutRevokesToken() throws Exception {
        registerPlayer("logouter", "logout@example.com");
        String refreshToken = loginAndGetBody("logout@example.com", VALID_PASSWORD)
                .at("/data/refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Revoked server side...looked up by hash, because the raw token is not stored.
        String tokenHash = refreshTokenGenerator.hash(refreshToken);
        assertThat(refreshTokenRepository.findByTokenHash(tokenHash))
                .hasValueSatisfying(token -> assertThat(token.isRevoked()).isTrue());

        // ...and therefore useless.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout is idempotent and never discloses whether the token existed")
    void logoutIsIdempotent() throws Exception {
        registerPlayer("idempotent", "idempotent@example.com");
        String refreshToken = loginAndGetBody("idempotent@example.com", VALID_PASSWORD)
                .at("/data/refreshToken").asText();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshPayload(refreshToken))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Logged out successfully"));
        }
    }

    @Test
    @DisplayName("rejects an unknown refresh token with 401")
    void rejectsUnknownRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshPayload("not-a-real-token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a refresh request with no token with 400")
    void rejectsBlankRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.refreshToken").exists());
    }
}