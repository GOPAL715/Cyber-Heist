package com.cyberheist.puzzle;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.mission.MissionCategory;
import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.player.PlayerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The puzzle loop end to end, through the real HTTP surface.
 *
 * <p>Where the provider tests check that generators produce well-formed
 * puzzles, this checks the contract a player actually experiences: start gives
 * a solvable challenge, the right answer pays, and every wrong path pays
 * nothing.
 */
class PuzzleLifecycleIntegrationTest extends IntegrationTestSupport {

    /** Starts a mission and returns the live puzzle row for it. */
    private PuzzleAttempt startAndGetPuzzle(String token, String email, String code) throws Exception {
        startMission(token, code);
        return latestPuzzle(email, missionId(code));
    }

    @Test
    @DisplayName("the mission's own puzzle family is the one it generates")
    void missionDecidesThePuzzleFamily() throws Exception {
        assertThat(missionRepository.findByCode("CRYPTO_TRANSMISSION").orElseThrow().getPuzzleType())
                .isEqualTo(PuzzleType.CIPHER);
        assertThat(missionRepository.findByCode("RECON_PERIMETER").orElseThrow().getPuzzleType())
                .isEqualTo(PuzzleType.SEQUENCE);
        assertThat(missionRepository.findByCode("INTEL_RECOVER_DATA").orElseThrow().getPuzzleType())
                .isEqualTo(PuzzleType.LOGIC);
        assertThat(missionRepository.findByCode("NETWORK_FIREWALL").orElseThrow().getPuzzleType())
                .isEqualTo(PuzzleType.PATTERN);
        assertThat(missionRepository.findByCode("EXPLOIT_WEAK_LINK").orElseThrow().getPuzzleType())
                .isEqualTo(PuzzleType.TIMED);
    }

    @ParameterizedTest
    @CsvSource({
            "CRYPTO_TRANSMISSION, CIPHER",
            "RECON_PERIMETER,   SEQUENCE",
            "INTEL_INFORMANT,   LOGIC",
            "NETWORK_SIGNAL,    PATTERN",
            "EXPLOIT_WEAK_LINK, TIMED"
    })
    @DisplayName("each puzzle family is generated and is solvable by the client")
    void everyFamilyIsGeneratedAndSolvable(String code, PuzzleType expected) throws Exception {
        String email = "fam" + expected.name().toLowerCase(java.util.Locale.ROOT) + "@example.com";
        String token = signInNewPlayer("fam" + expected.name().toLowerCase(java.util.Locale.ROOT), email);

        // Level gating is covered by the Phase 2 progression tests; here the
        // player simply needs to be high enough to reach each tier.
        grantExperience(email, 2000);

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId(code) + "/start", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.puzzle.type").value(expected.name()))
                .andExpect(jsonPath("$.data.puzzle.sequence").isNotEmpty())
                .andExpect(jsonPath("$.data.puzzle.question").isNotEmpty());

        var puzzle = latestPuzzle(email, missionId(code));
        assertThat(puzzle.getPuzzleType()).isEqualTo(expected);
        assertThat(puzzle.getStatus()).isEqualTo(PuzzleAttemptStatus.ACTIVE);
    }

    @Test
    @DisplayName("start -> puzzle generated -> correct answer -> mission completed -> rewards paid")
    void fullHappyPath() throws Exception {
        String token = signInNewPlayer("happy", "happy@example.com");
        UUID missionId = missionId("CRYPTO_TRANSMISSION");
        var mission = missionRepository.findById(missionId).orElseThrow();
        PlayerProfile before = profileOf("happy@example.com");

        JsonNode start = startMission(token, "CRYPTO_TRANSMISSION");
        var puzzle = latestPuzzle("happy@example.com", missionId);

        assertThat(start.path("puzzle").path("puzzleId").asText())
                .isEqualTo(puzzle.getPuzzleId().toString());
        assertThat(start.path("puzzle").path("type").asText()).isEqualTo("CIPHER");
        assertThat(start.path("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode result = submitPuzzle(token, missionId, puzzle.getPuzzleId(), correctAnswerFor(puzzle));

        assertThat(result.path("outcome").asText()).isEqualTo("SOLVED");
        assertThat(result.path("missionCompleted").asBoolean()).isTrue();
        assertThat(result.path("rewards").path("experience").asLong()).isEqualTo(mission.getXpReward());
        assertThat(result.path("rewards").path("coins").asLong()).isEqualTo(mission.getCoinReward());

        assertThat(progressRepository
                .findByUserIdAndMissionId(userIdOf("happy@example.com"), missionId)
                .orElseThrow().getStatus()).isEqualTo(com.cyberheist.mission.MissionStatus.COMPLETED);

        PlayerProfile after = profileOf("happy@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("start -> wrong answer -> no reward, mission still running")
    void wrongAnswerGrantsNothing() throws Exception {
        String token = signInNewPlayer("nope", "nope@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        var mission = missionRepository.findById(missionId).orElseThrow();
        PlayerProfile before = profileOf("nope@example.com");

        startMission(token, "RECON_PERIMETER");
        var puzzle = latestPuzzle("nope@example.com", missionId);

        JsonNode result = submitPuzzle(token, missionId, puzzle.getPuzzleId(), "DEFINITELY-WRONG");

        assertThat(result.path("outcome").asText()).isEqualTo("INCORRECT");
        assertThat(result.path("rewards").path("experience").asLong()).isZero();
        assertThat(result.path("rewards").path("coins").asLong()).isZero();
        assertThat(result.path("missionCompleted").asBoolean()).isFalse();
        assertThat(result.path("canRetry").asBoolean()).isTrue();

        PlayerProfile after = profileOf("nope@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
        assertThat(after.getCoins()).isEqualTo(before.getCoins());

        // The puzzle is spent, so a second answer to it is a conflict.
        assertThat(latestPuzzle("nope@example.com", missionId).getStatus())
                .isEqualTo(PuzzleAttemptStatus.FAILED);
        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(puzzle.getPuzzleId(), correctAnswerFor(puzzle)))))
                .andExpect(status().isConflict());

        // Restarting supersedes the failed puzzle and offers a fresh one.
        JsonNode restarted = startMission(token, "RECON_PERIMETER");
        assertThat(restarted.path("attemptCount").asInt()).isEqualTo(2);
        assertThat(latestPuzzle("nope@example.com", missionId).getStatus())
                .isEqualTo(PuzzleAttemptStatus.ACTIVE);

        submitPuzzle(token, missionId, latestPuzzle("nope@example.com", missionId).getPuzzleId(),
                correctAnswerFor(latestPuzzle("nope@example.com", missionId)));
        assertThat(profileOf("nope@example.com").getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
    }

    @Test
    @DisplayName("start -> expiry -> no reward, even with the right answer")
    void expiryGrantsNothing() throws Exception {
        String token = signInNewPlayer("expiry", "expiry@example.com");
        UUID missionId = missionId("EXPLOIT_WEAK_LINK");
        var mission = missionRepository.findById(missionId).orElseThrow();

        // The timed family sits behind a level gate; the case under test is
        // expiry, so the player is simply given the level. The baseline is taken
        // afterwards so the assertion is about the puzzle, not the grant.
        grantExperience("expiry@example.com", 2000);
        PlayerProfile before = profileOf("expiry@example.com");

        startMission(token, "EXPLOIT_WEAK_LINK");
        var puzzle = latestPuzzle("expiry@example.com", missionId);
        String answer = correctAnswerFor(puzzle);

        // Close the window the way time would, rather than waiting it out.
        expirePuzzleWindow(puzzle.getPuzzleId());

        JsonNode result = submitPuzzle(token, missionId, puzzle.getPuzzleId(), answer);

        assertThat(result.path("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(result.path("rewards").path("experience").asLong()).isZero();
        assertThat(result.path("rewards").path("coins").asLong()).isZero();
        assertThat(result.path("missionCompleted").asBoolean()).isFalse();

        PlayerProfile after = profileOf("expiry@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
        assertThat(after.getCoins()).isEqualTo(before.getCoins());

        assertThat(progressRepository
                .findByUserIdAndMissionId(userIdOf("expiry@example.com"), missionId)
                .orElseThrow().getStatus()).isEqualTo(com.cyberheist.mission.MissionStatus.IN_PROGRESS);
        assertThat(mission.getXpReward()).isPositive();
    }

    @Test
    @DisplayName("an answer submitted inside the window is accepted")
    void answerBeforeExpiryIsAccepted() throws Exception {
        String token = signInNewPlayer("intime", "intime@example.com");
        UUID missionId = missionId("CRYPTO_TRANSMISSION");

        startMission(token, "CRYPTO_TRANSMISSION");
        var puzzle = latestPuzzle("intime@example.com", missionId);

        assertThat(puzzle.isExpiredAt(Instant.now()))
                .as("a freshly issued puzzle must have a live window")
                .isFalse();

        JsonNode result = submitPuzzle(token, missionId, puzzle.getPuzzleId(), correctAnswerFor(puzzle));
        assertThat(result.path("outcome").asText()).isEqualTo("SOLVED");
    }

    @Test
    @DisplayName("no response ever contains a free-text answer")
    void noResponseLeaksTheAnswer() throws Exception {
        String token = signInNewPlayer("leaky", "leaky@example.com");
        // A free-text puzzle, where the answer is a single string that could
        // therefore be searched for in the payload. A multiple-choice puzzle
        // necessarily lists the right answer among its options - that is what
        // makes it multiple choice - so the meaningful check there is that no
        // field marks which one it is, covered below.
        UUID missionId = missionId("CRYPTO_TRANSMISSION");

        String startBody = mockMvc.perform(
                        authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var puzzle = latestPuzzle("leaky@example.com", missionId);
        String answer = correctAnswerFor(puzzle);
        assertThat(puzzle.getPuzzleType()).isEqualTo(PuzzleType.CIPHER);

        assertThat(startBody)
                .as("the start response must not contain the answer")
                .doesNotContain(answer);

        // The active-puzzle endpoint re-derives the challenge from the seed and
        // must drop the answer just the same.
        String activeBody = mockMvc.perform(
                        authGet("/api/v1/player/missions/" + missionId + "/puzzle", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(activeBody).doesNotContain(answer);

        // And neither does a wrong-answer submission, which must not help a
        // player brute-force by explaining what was expected.
        String failedBody = mockMvc.perform(
                        authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new PuzzlePayload(puzzle.getPuzzleId(), "WRONG"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(failedBody).doesNotContain(answer);

        assertNoAnswerFieldNames(startBody);
        assertNoAnswerFieldNames(activeBody);
        assertNoAnswerFieldNames(failedBody);
    }

    @Test
    @DisplayName("a multiple-choice puzzle offers options without marking the right one")
    void multipleChoiceDoesNotMarkTheAnswer() throws Exception {
        String token = signInNewPlayer("choice", "choice@example.com");
        UUID missionId = missionId("RECON_NETWORK_MAP");

        String startBody = mockMvc.perform(
                        authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var puzzle = latestPuzzle("choice@example.com", missionId);
        assertThat(puzzle.getPuzzleType()).isEqualTo(PuzzleType.SEQUENCE);

        JsonNode data = objectMapper.readTree(startBody).path("data").path("puzzle");
        assertThat(data.path("options")).isNotEmpty();

        // The right answer is unavoidably one of the options - that is the
        // format - so the guarantee is that nothing identifies which.
        assertThat(data.path("options").toString()).contains(correctAnswerFor(puzzle));
        assertNoAnswerFieldNames(startBody);
    }

    /** Fails if any field name in the payload promises an answer. */
    private static void assertNoAnswerFieldNames(String body) {
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        assertThat(lower)
                .doesNotContain("correctanswer")
                .doesNotContain("expectedanswer")
                .doesNotContain("\"answer\"")
                .doesNotContain("solution")
                .doesNotContain("\"seed\"");
    }

    @Test
    @DisplayName("the active-puzzle endpoint rebuilds the same challenge from the seed")
    void activePuzzleIsStable() throws Exception {
        String token = signInNewPlayer("stable", "stable@example.com");
        UUID missionId = missionId("CRYPTO_TRANSMISSION");

        JsonNode start = startMission(token, "CRYPTO_TRANSMISSION");
        JsonNode active = objectMapper.readTree(mockMvc.perform(
                        authGet("/api/v1/player/missions/" + missionId + "/puzzle", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");

        assertThat(active.path("puzzleId").asText()).isEqualTo(start.path("puzzle").path("puzzleId").asText());
        assertThat(active.path("sequence")).isEqualTo(start.path("puzzle").path("sequence"));
        assertThat(active.path("question").asText())
                .isEqualTo(start.path("puzzle").path("question").asText());
    }

    @Test
    @DisplayName("a mission with no puzzle yet reports none rather than inventing one")
    void activePuzzleIsAbsentBeforeStarting() throws Exception {
        String token = signInNewPlayer("nopuzzle", "nopuzzle@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions/"
                        + missionId("RECON_PERIMETER") + "/puzzle", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Puzzle not found for this mission"));
    }

    @Test
    @DisplayName("a malformed submission body is rejected before anything happens")
    void rejectsMalformedSubmissions() throws Exception {
        String token = signInNewPlayer("malformed", "malformed@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        startMission(token, "RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.puzzleId").exists());

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puzzleId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.answer").exists());

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("every seeded mission exposes a puzzle family the engine can serve")
    void everyMissionHasAServablePuzzle() {
        assertThat(missionRepository.findAll())
                .isNotEmpty()
                .allSatisfy(mission -> {
                    assertThat(PuzzleType.values()).contains(mission.getPuzzleType());
                    assertThat(MissionDifficulty.values()).contains(mission.getDifficulty());
                });

        // All five families must actually be reachable in the seeded catalogue,
        // otherwise a type would ship untested in real play.
        assertThat(missionRepository.findAll().stream()
                .map(com.cyberheist.mission.Mission::getPuzzleType)
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(PuzzleType.values());

        assertThat(MissionCategory.values()).hasSize(5);
    }
}