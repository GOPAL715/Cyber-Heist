package com.cyberheist.auth;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration endpoint: validation, uniqueness, and side effects. */
class RegistrationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PlayerProfileRepository playerProfileRepository;

    @Test
    @DisplayName("registers a new player, returns 201 and no password")
    void registersSuccessfully() throws Exception {
        String email = "newplayer@example.com";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("newhacker", email, VALID_PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("newhacker"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("PLAYER"))
                // The response must never echo the password back.
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        User saved = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("newhacker");
        assertThat(saved.getRole().name()).isEqualTo("PLAYER");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("stores the password as a BCrypt hash, never as plaintext")
    void hashesPassword() throws Exception {
        User saved = registerPlayer("hashcheck", "hashcheck@example.com");

        assertThat(saved.getPasswordHash())
                .isNotEqualTo(VALID_PASSWORD)
                .startsWith("$2");
        assertThat(passwordEncoder.matches(VALID_PASSWORD, saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("creates the player profile automatically with the starting values")
    void createsPlayerProfile() throws Exception {
        User saved = registerPlayer("profilecheck", "profilecheck@example.com");

        assertThat(playerProfileRepository.findByUserId(saved.getId()))
                .hasValueSatisfying(profile -> {
                    assertThat(profile.getLevel()).isEqualTo(1);
                    assertThat(profile.getExperience()).isZero();
                    assertThat(profile.getCoins()).isEqualTo(100);
                    assertThat(profile.getEnergy()).isEqualTo(100);
                    assertThat(profile.getDisplayName()).isEqualTo("profilecheck");
                });
    }
    @Test
    @DisplayName("rejects a duplicate email with 409")
    void rejectsDuplicateEmail() throws Exception {
        registerPlayer("firstuser", "duplicate@example.com");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("seconduser", "duplicate@example.com", VALID_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    @DisplayName("treats email case-insensitively when detecting duplicates")
    void rejectsDuplicateEmailIgnoringCase() throws Exception {
        registerPlayer("caseuser", "CaseUser@example.com");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("othercase", "caseuser@EXAMPLE.com", VALID_PASSWORD))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("rejects a duplicate username with 409")
    void rejectsDuplicateUsername() throws Exception {
        registerPlayer("takenname", "first@example.com");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("takenname", "second@example.com", VALID_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username is already taken"));
    }

    @Test
    @DisplayName("rejects a malformed email with 400")
    void rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("validname", "not-an-email", VALID_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @DisplayName("rejects a weak password with 400")
    void rejectsWeakPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("weakpass", "weak@example.com", "password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("rejects missing fields with 400 and lists each one")
    void rejectsMissingFields() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("stacktrace");
    }

    @Test
    @DisplayName("rejects a blank username with 400")
    void rejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationPayload("   ", "blank@example.com", VALID_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    @DisplayName("rejects malformed JSON with 400")
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}