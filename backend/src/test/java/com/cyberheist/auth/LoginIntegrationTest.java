package com.cyberheist.auth;

import com.cyberheist.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login endpoint: credential verification and token issuance. */
class LoginIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("returns an access/refresh token pair for correct credentials")
    void loginSucceeds() throws Exception {
        registerPlayer("loginguys", "login@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("login@example.com", VALID_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.username").value("loginguys"))
                .andExpect(jsonPath("$.data.user.role").value("PLAYER"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("issues a signed JWT that carries the expected claims")
    void accessTokenIsAJwt() throws Exception {
        registerPlayer("claimuser", "claims@example.com");

        JsonNode body = loginAndGetBody("claims@example.com", VALID_PASSWORD);
        String accessToken = body.at("/data/accessToken").asText();

        String[] parts = accessToken.split("\\.");
        assertThat(parts).hasSize(3);

        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload).contains("\"sub\"");
        assertThat(payload).contains("\"role\":\"PLAYER\"");
        assertThat(payload).contains("\"exp\"");
        assertThat(payload).contains("\"iat\"");
        // No password material may ever appear inside the token.
        assertThat(payload).doesNotContain(VALID_PASSWORD);
        assertThat(payload).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("rejects an incorrect password with 401")
    void rejectsWrongPassword() throws Exception {
        registerPlayer("wrongpw", "wrongpw@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("wrongpw@example.com", "Wr0ng!Password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("rejects an unknown account with the same 401 message")
    void rejectsUnknownUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("ghost@example.com", VALID_PASSWORD))))
                .andExpect(status().isUnauthorized())
                // Identical wording, so the API cannot be used to enumerate accounts.
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("rejects a disabled account with 401")
    void rejectsDisabledUser() throws Exception {
        registerDisabledPlayer("disableduser", "disabled@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("disabled@example.com", VALID_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This account has been disabled"));
    }

    @Test
    @DisplayName("rejects a login request missing fields with 400")
    void rejectsMissingCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("accepts the email in any casing")
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        registerPlayer("caselogin", "CaseLogin@Example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("caselogin@EXAMPLE.COM", VALID_PASSWORD))))
                .andExpect(status().isOk());
    }
}