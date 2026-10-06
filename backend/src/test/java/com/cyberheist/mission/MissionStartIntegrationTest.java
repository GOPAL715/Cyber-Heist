package com.cyberheist.mission;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Starting a mission: eligibility, energy cost and progress transitions. */
class MissionStartIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a valid mission starts, deducting its energy cost")
    void startsMissionAndSpendsEnergy() throws Exception {
        String token = signInNewPlayer("starter", "starter@example.com");
        PlayerProfile before = profileOf("starter@example.com");
        int energyBefore = before.getEnergy();

        Mission mission = missionRepository.findByCode("RECON_PERIMETER").orElseThrow();

        mockMvc.perform(authPost("/api/v1/player/missions/" + mission.getId() + "/start", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.code").value("RECON_PERIMETER"));

        PlayerProfile after = profileOf("starter@example.com");
        assertThat(after.getEnergy()).isEqualTo(energyBefore - mission.getEnergyCost());
        assertThat(after.getCoins()).isEqualTo(before.getCoins());
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
    }

    @Test
    @DisplayName("starting a mission hands the player a puzzle with no answer in it")
    void startReturnsAPuzzle() throws Exception {
        String token = signInNewPlayer("puzzled", "puzzled@example.com");
        UUID missionId = missionId("CRYPTO_TRANSMISSION");

        String body = mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.puzzle.puzzleId").isNotEmpty())
                .andExpect(jsonPath("$.data.puzzle.type").value("CIPHER"))
                .andExpect(jsonPath("$.data.puzzle.difficulty").value("EASY"))
                .andExpect(jsonPath("$.data.puzzle.question").isNotEmpty())
                .andExpect(jsonPath("$.data.puzzle.sequence").isArray())
                .andExpect(jsonPath("$.data.puzzle.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.puzzle.timeLimitSeconds").isNumber())
                // The energy figures the board needs to explain a refusal.
                .andExpect(jsonPath("$.data.player.energy").isNumber())
                .andExpect(jsonPath("$.data.player.maximum").value(100))
                .andReturn().getResponse().getContentAsString();

        // The single most important property of the whole feature: nothing in
        // the payload names the answer, under any spelling.
        assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("correctanswer")
                .doesNotContain("\"answer\"")
                .doesNotContain("expectedanswer")
                .doesNotContain("solution");

        var puzzle = latestPuzzle("puzzled@example.com", missionId);
        assertThat(correctAnswerFor(puzzle)).isNotBlank();
        assertThat(body).doesNotContain(correctAnswerFor(puzzle));
    }

    @Test
    @DisplayName("starting records IN_PROGRESS with a start timestamp")
    void recordsProgress() throws Exception {
        String token = signInNewPlayer("recorder", "recorder@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        UUID userId = userRepository.findByEmailIgnoreCase("recorder@example.com")
                .orElseThrow().getId();

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk());

        var progress = progressRepository.findByUserIdAndMissionId(userId, missionId).orElseThrow();
        assertThat(progress.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progress.getStartedAt()).isNotNull();
        assertThat(progress.getCompletedAt()).isNull();
        assertThat(progress.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("starting requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/player/missions/" + missionId("RECON_PERIMETER") + "/start"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a mission above the player's level is refused with 403")
    void enforcesRequiredLevel() throws Exception {
        String token = signInNewPlayer("tooslow", "tooslow@example.com");
        UUID missionId = missionId("INTEL_RECOVER_DATA"); // requires level 6

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Requires level 6"));
    }

    @Test
    @DisplayName("a refused start costs no energy")
    void refusedStartCostsNothing() throws Exception {
        String token = signInNewPlayer("nocost", "nocost@example.com");
        int energyBefore = profileOf("nocost@example.com").getEnergy();

        mockMvc.perform(authPost(
                        "/api/v1/player/missions/" + missionId("INTEL_RECOVER_DATA") + "/start", token))
                .andExpect(status().isForbidden());

        assertThat(profileOf("nocost@example.com").getEnergy()).isEqualTo(energyBefore);
    }

    @Test
    @DisplayName("an inactive mission cannot be started")
    void rejectsInactiveMission() throws Exception {
        Mission mission = missionRepository.findByCode("RECON_PERIMETER").orElseThrow();
        mission.deactivate();
        missionRepository.saveAndFlush(mission);

        try {
            String token = signInNewPlayer("inactivestart", "inactivestart@example.com");

            mockMvc.perform(authPost("/api/v1/player/missions/" + mission.getId() + "/start", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This mission is not currently available"));
        } finally {
            mission.activate();
            missionRepository.saveAndFlush(mission);
        }
    }

    @Test
    @DisplayName("an unknown mission cannot be started")
    void rejectsUnknownMission() throws Exception {
        String token = signInNewPlayer("ghoststart", "ghoststart@example.com");

        mockMvc.perform(authPost("/api/v1/player/missions/" + randomUuid() + "/start", token))
                .andExpect(status().isNotFound());
    }
    @Test
    @DisplayName("insufficient energy is refused with 400 and energy never goes negative")
    void rejectsInsufficientEnergy() throws Exception {
        String token = signInNewPlayer("drained", "drained@example.com");
        UUID missionId = missionId("INTEL_RECOVER_DATA"); // 40 energy, needs level 7

        // Drain the player below the cost of any mission.
        PlayerProfile profile = profileOf("drained@example.com");
        profile.spendEnergy(profile.getEnergy());
        profileRepository.saveAndFlush(profile);
        assertThat(profile.getEnergy()).isZero();

        // A level 1 mission costing 10 energy can no longer be afforded.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId("RECON_PERIMETER") + "/start", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Not enough energy: this mission costs 10"));

        assertThat(profileOf("drained@example.com").getEnergy()).isZero();
    }

    @Test
    @DisplayName("a completed mission cannot be restarted")
    void refusesToRestartCompletedMission() throws Exception {
        String token = signInNewPlayer("finisher", "finisher@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk());
        completeMissionThroughPuzzle(token, missionId, "finisher@example.com");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This mission has already been completed"));
    }

    @Test
    @DisplayName("a mission cannot be completed before it is started")
    void completingWithoutStartingIsRefused() throws Exception {
        String token = signInNewPlayer("jumper", "jumper@example.com");

        mockMvc.perform(authPost(
                        "/api/v1/player/missions/" + missionId("RECON_PERIMETER") + "/complete", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This mission has not been started"));
    }

    @Test
    @DisplayName("restarting an in-progress mission charges energy again and replaces the puzzle")
    void restartingInProgressMission() throws Exception {
        String token = signInNewPlayer("retryer", "retryer@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        PlayerProfile profile = profileOf("retryer@example.com");
        int energyBefore = profile.getEnergy();

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk());
        var firstPuzzle = latestPuzzle("retryer@example.com", missionId);

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk());
        var secondPuzzle = latestPuzzle("retryer@example.com", missionId);

        // Each start costs energy, so a double-click is never free.
        assertThat(profileOf("retryer@example.com").getEnergy())
                .isEqualTo(energyBefore - 20);

        assertThat(progressRepository.findByUserIdAndMissionId(userIdOf("retryer@example.com"), missionId)
                .orElseThrow().getAttemptCount()).isEqualTo(2);

        // The superseded puzzle is closed, and the replacement cannot answer for it.
        assertThat(firstPuzzle.getPuzzleId()).isNotEqualTo(secondPuzzle.getPuzzleId());
        assertThat(secondPuzzle.getAttemptNumber()).isEqualTo(2);

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(firstPuzzle.getPuzzleId(),
                                        correctAnswerFor(firstPuzzle)))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("exactly enough energy is accepted and leaves zero")
    void acceptsExactlySufficientEnergy() throws Exception {
        String token = signInNewPlayer("exact", "exact@example.com");
        PlayerProfile profile = profileOf("exact@example.com");
        // RECON_PERIMETER costs 10.
        profile.spendEnergy(profile.getEnergy() - 10);
        profileRepository.saveAndFlush(profile);
        assertThat(profile.getEnergy()).isEqualTo(10);

        mockMvc.perform(authPost(
                        "/api/v1/player/missions/" + missionId("RECON_PERIMETER") + "/start", token))
                .andExpect(status().isOk());

        assertThat(profileOf("exact@example.com").getEnergy()).isZero();
    }
}