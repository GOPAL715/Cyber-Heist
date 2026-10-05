package com.cyberheist.player;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Player profile endpoint: ownership and initial values. */
class PlayerProfileIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("returns the authenticated player's own profile")
    void returnsOwnProfile() throws Exception {
        registerPlayer("profiletester", "profile@example.com");
        String token = loginAndGetAccessToken("profile@example.com", VALID_PASSWORD);

        mockMvc.perform(get("/api/v1/player/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("profiletester"))
                .andExpect(jsonPath("$.data.level").value(1))
                .andExpect(jsonPath("$.data.experience").value(0))
                .andExpect(jsonPath("$.data.coins").value(100))
                .andExpect(jsonPath("$.data.energy").value(100));
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/player/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("a player only ever receives their own profile, never another player's")
    void cannotReadAnotherPlayersProfile() throws Exception {
        User victim = registerPlayer("victimplayer", "victim@example.com");
        registerPlayer("attacker", "attacker@example.com");

        String attackerToken = loginAndGetAccessToken("attacker@example.com", VALID_PASSWORD);

        String body = mockMvc.perform(get("/api/v1/player/profile")
                        .header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The caller gets their own data...
        assertThat(body).contains("attacker");
        // ...and nothing belonging to the other account.
        assertThat(body).doesNotContain("victimplayer");
        assertThat(body).doesNotContain(victim.getId().toString());
    }

    @Test
    @DisplayName("ignores an attempt to read a profile by another player's id")
    void ignoresUserIdParameter() throws Exception {
        User victim = registerPlayer("queryvictim", "queryvictim@example.com");
        registerPlayer("queryattacker", "queryattacker@example.com");
        String attackerToken = loginAndGetAccessToken("queryattacker@example.com", VALID_PASSWORD);

        mockMvc.perform(get("/api/v1/player/profile")
                        .param("userId", victim.getId().toString())
                        .param("id", victim.getId().toString())
                        .header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("queryattacker"));
    }

    @Test
    @DisplayName("two players see distinct profiles")
    void profilesAreIsolated() throws Exception {
        registerPlayer("playerone", "one@example.com");
        registerPlayer("playertwo", "two@example.com");

        String firstToken = loginAndGetAccessToken("one@example.com", VALID_PASSWORD);
        String secondToken = loginAndGetAccessToken("two@example.com", VALID_PASSWORD);

        mockMvc.perform(get("/api/v1/player/profile")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(jsonPath("$.data.username").value("playerone"));

        mockMvc.perform(get("/api/v1/player/profile")
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(jsonPath("$.data.username").value("playertwo"));
    }
}