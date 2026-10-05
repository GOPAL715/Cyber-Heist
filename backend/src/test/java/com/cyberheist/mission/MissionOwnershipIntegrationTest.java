package com.cyberheist.mission;

import com.cyberheist.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One player must never be able to see or modify another player's mission
 * progress.
 */
class MissionOwnershipIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("progress is scoped to the caller, not to any user id")
    void progressIsScopedToTheCaller() throws Exception {
        String victimToken = signInNewPlayer("ownervictim", "ownervictim@example.com");
        String attackerToken = signInNewPlayer("ownerattacker", "ownerattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        // The victim starts and completes the mission.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/complete", victimToken))
                .andExpect(status().isOk());

        // The attacker's own view of the same mission is untouched.
        mockMvc.perform(authGet("/api/v1/player/missions/" + missionId, attackerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"));
    }

    @Test
    @DisplayName("the progress endpoint reports only the caller's own progress")
    void progressEndpointIsScoped() throws Exception {
        String victimToken = signInNewPlayer("progvictim", "progvictim@example.com");
        String attackerToken = signInNewPlayer("progattacker", "progattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());

        String attackerBody = mockMvc.perform(
                        authGet("/api/v1/player/missions/" + missionId + "/progress", attackerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(attackerBody).doesNotContain("IN_PROGRESS");
    }

    @Test
    @DisplayName("supplying another player's user id as a parameter changes nothing")
    void ignoresSuppliedUserId() throws Exception {
        String victimToken = signInNewPlayer("paramvictim", "paramvictim@example.com");
        String attackerToken = signInNewPlayer("paramattacker", "paramattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        UUID victimId = userRepository.findByEmailIgnoreCase("paramvictim@example.com")
                .orElseThrow().getId();

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/complete", victimToken))
                .andExpect(status().isOk());

        // The attacker tries to read the victim's mission by passing their id.
        mockMvc.perform(authGet("/api/v1/player/missions/" + missionId
                        + "?userId=" + victimId, attackerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.startable").value(true));

        mockMvc.perform(authGet("/api/v1/player/missions?userId=" + victimId, attackerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='RECON_PERIMETER')].status")
                        .value("NOT_STARTED"));
    }
    @Test
    @DisplayName("an attacker cannot complete a mission on the victim's behalf")
    void cannotCompleteOnBehalfOfAnotherPlayer() throws Exception {
        String victimToken = signInNewPlayer("bevictim", "bevictim@example.com");
        String attackerToken = signInNewPlayer("beattacker", "beattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        // The victim starts it; the attacker has no progress row of their own.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/complete", attackerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This mission has not been started"));
    }

    @Test
    @DisplayName("there is no endpoint that takes a user id in the path")
    void noPerUserMissionEndpointExists() throws Exception {
        String token = signInNewPlayer("nopath", "nopath@example.com");
        UUID victimId = userRepository.findByEmailIgnoreCase("paramvictim@example.com")
                .orElseThrow().getId();

        // /player/{userId}/missions must not exist.
        mockMvc.perform(authGet("/api/v1/player/" + victimId + "/missions", token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("rewards reach only the caller")
    void rewardGoesOnlyToTheCaller() throws Exception {
        String victimToken = signInNewPlayer("rewardvictim", "rewardvictim@example.com");
        String attackerToken = signInNewPlayer("rewardattacker", "rewardattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        Mission mission = missionRepository.findById(missionId).orElseThrow();

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/complete", victimToken))
                .andExpect(status().isOk());

        // The victim gained the reward; the attacker gained nothing.
        assertThat(profileOf("rewardvictim@example.com").getCoins())
                .isEqualTo(100 + mission.getCoinReward());
        assertThat(profileOf("rewardattacker@example.com").getCoins()).isEqualTo(100);
        assertThat(profileOf("rewardattacker@example.com").getExperience()).isZero();
    }
}