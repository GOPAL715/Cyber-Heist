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
        completeMissionThroughPuzzle(victimToken, missionId, "ownervictim@example.com");

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
        completeMissionThroughPuzzle(victimToken, missionId, "paramvictim@example.com");

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
    @DisplayName("an attacker cannot submit another player's puzzle")
    void cannotSubmitAnotherPlayersPuzzle() throws Exception {
        String victimToken = signInNewPlayer("pzvictim", "pzvictim@example.com");
        String attackerToken = signInNewPlayer("pzattacker", "pzattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        // The victim starts the mission, so a real puzzle exists.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());
        var victimPuzzle = latestPuzzle("pzvictim@example.com", missionId);

        // The attacker never started this mission, and is refused twice over:
        // the puzzle is not theirs, and the mission is not in progress for them.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", attackerToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(victimPuzzle.getPuzzleId(),
                                        correctAnswerFor(victimPuzzle)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Puzzle not found for this mission"));

        // The victim's puzzle is untouched and still theirs to solve.
        assertThat(latestPuzzle("pzvictim@example.com", missionId).getStatus())
                .isEqualTo(com.cyberheist.puzzle.PuzzleAttemptStatus.ACTIVE);
        assertThat(profileOf("pzvictim@example.com").getExperience()).isZero();
        assertThat(profileOf("pzattacker@example.com").getExperience()).isZero();
    }
    @Test
    @DisplayName("an attacker cannot complete a mission on the victim's behalf")
    void cannotCompleteOnBehalfOfAnotherPlayer() throws Exception {
        String victimToken = signInNewPlayer("bevictim", "bevictim@example.com");
        String attackerToken = signInNewPlayer("beattacker", "beattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");// The victim starts it; the attacker has no progress row of their own.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/complete", attackerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This mission has not been started"));
    }

    @Test
    @DisplayName("an attacker cannot advance another player's puzzle by id alone")
    void attackerCannotCompleteOnBehalfOfAnotherPlayer() throws Exception {
        String victimToken = signInNewPlayer("pz2victim", "pz2victim@example.com");
        String attackerToken = signInNewPlayer("pz2attacker", "pz2attacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", victimToken))
                .andExpect(status().isOk());
        var victimPuzzle = latestPuzzle("pz2victim@example.com", missionId);

        // Correct answer, right mission, wrong player: still refused.
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", attackerToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(victimPuzzle.getPuzzleId(),
                                        correctAnswerFor(victimPuzzle)))))
                .andExpect(status().isNotFound());

        assertThat(profileOf("pz2victim@example.com").getExperience()).isZero();
        assertThat(profileOf("pz2attacker@example.com").getExperience()).isZero();
    }

    @Test
    @DisplayName("a puzzle from another mission is rejected")
    void cannotSubmitAPuzzleFromAnotherMission() throws Exception {
        String token = signInNewPlayer("crossmission", "crossmission@example.com");
        UUID first = missionId("RECON_PERIMETER");
        UUID second = missionId("RECON_NETWORK_MAP");

        // The player starts both missions and holds a live puzzle on each.
        mockMvc.perform(authPost("/api/v1/player/missions/" + first + "/start", token))
                .andExpect(status().isOk());
        mockMvc.perform(authPost("/api/v1/player/missions/" + second + "/start", token))
                .andExpect(status().isOk());

        var firstPuzzle = latestPuzzle("crossmission@example.com", first);
        var secondPuzzle = latestPuzzle("crossmission@example.com", second);
        assertThat(firstPuzzle.getPuzzleId()).isNotEqualTo(secondPuzzle.getPuzzleId());

        // Answering mission A's puzzle while claiming to be on mission B must not
        // solve A either: the mismatch is refused before the answer is read.
        mockMvc.perform(authPost("/api/v1/player/missions/" + second + "/puzzle/submit", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(firstPuzzle.getPuzzleId(), correctAnswerFor(firstPuzzle)))))
                .andExpect(status().isNotFound());

        assertThat(latestPuzzle("crossmission@example.com", first).getStatus())
                .isEqualTo(com.cyberheist.puzzle.PuzzleAttemptStatus.ACTIVE);
        assertThat(profileOf("crossmission@example.com").getExperience()).isZero();
    }

    @Test
    @DisplayName("a made-up puzzle id is rejected")
    void cannotSubmitAFakePuzzleId() throws Exception {
        String token = signInNewPlayer("fakepz", "fakepz@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk());

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(randomUuid(), "ANYTHING"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Puzzle not found for this mission"));

        assertThat(profileOf("fakepz@example.com").getExperience()).isZero();
        assertThat(profileOf("fakepz@example.com").getCoins()).isEqualTo(100);
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
        completeMissionThroughPuzzle(victimToken, missionId, "rewardvictim@example.com");

        // The victim gained the reward; the attacker gained nothing.
        assertThat(profileOf("rewardvictim@example.com").getCoins())
                .isEqualTo(100 + mission.getCoinReward());
        assertThat(profileOf("rewardattacker@example.com").getCoins()).isEqualTo(100);
        assertThat(profileOf("rewardattacker@example.com").getExperience()).isZero();
    }
}