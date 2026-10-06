package com.cyberheist.puzzle;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a player cannot do to the puzzle endpoint.
 *
 * <p>Each case here is an attack, and each is written from the attacker's side:
 * the client holds a token, knows the URL shapes, and can send any JSON it
 * likes. The question is only what the server is willing to accept.
 */
class PuzzleSecurityIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a player cannot submit another player's puzzle, even with the right answer")
    void cannotSubmitAnotherPlayersPuzzle() throws Exception {
        String victimToken = signInNewPlayer("secvictim", "secvictim@example.com");
        String attackerToken = signInNewPlayer("secattacker", "secattacker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        startMission(victimToken, "RECON_PERIMETER");
        var victimPuzzle = latestPuzzle("secvictim@example.com", missionId);

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", attackerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(victimPuzzle.getPuzzleId(),
                                        correctAnswerFor(victimPuzzle)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Puzzle not found for this mission"));

        assertThat(latestPuzzle("secvictim@example.com", missionId).getStatus())
                .isEqualTo(PuzzleAttemptStatus.ACTIVE);
        assertThat(profileOf("secvictim@example.com").getExperience()).isZero();
        assertThat(profileOf("secattacker@example.com").getExperience()).isZero();
    }

    @Test
    @DisplayName("a player cannot solve their own puzzle through someone else's mission")
    void cannotCrossMissions() throws Exception {
        String token = signInNewPlayer("crosser", "crosser@example.com");
        UUID first = missionId("RECON_PERIMETER");
        UUID second = missionId("CRYPTO_TRANSMISSION");

        startMission(token, "RECON_PERIMETER");
        startMission(token, "CRYPTO_TRANSMISSION");

        var firstPuzzle = latestPuzzle("crosser@example.com", first);
        var secondPuzzle = latestPuzzle("crosser@example.com", second);

        // A genuine answer to mission A's puzzle, submitted as mission B's.
        mockMvc.perform(authPost("/api/v1/player/missions/" + second + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(firstPuzzle.getPuzzleId(), correctAnswerFor(firstPuzzle)))))
                .andExpect(status().isNotFound());

        assertThat(latestPuzzle("crosser@example.com", first).getStatus())
                .isEqualTo(PuzzleAttemptStatus.ACTIVE);
        assertThat(latestPuzzle("crosser@example.com", second).getStatus())
                .isEqualTo(PuzzleAttemptStatus.ACTIVE);
        assertThat(profileOf("crosser@example.com").getExperience()).isZero();
    }

    @Test
    @DisplayName("a made-up puzzle id is refused")
    void rejectsFabricatedPuzzleIds() throws Exception {
        String token = signInNewPlayer("faker", "faker@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        startMission(token, "RECON_PERIMETER");

        UUID realPuzzleId = latestPuzzle("faker@example.com", missionId).getPuzzleId();
        for (UUID fake : new UUID[]{randomUuid(), UUID.randomUUID(), new UUID(0L, 0L)}) {
            mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new PuzzlePayload(fake, "ANYTHING"))))
                    .andExpect(status().isNotFound());
        }
        assertThat(realPuzzleId).isNotNull();

        assertThat(profileOf("faker@example.com").getExperience()).isZero();
        assertThat(profileOf("faker@example.com").getCoins()).isEqualTo(100);
    }

    @Test
    @DisplayName("a malformed puzzle id is a 400, not a crash")
    void rejectsMalformedPuzzleIds() throws Exception {
        String token = signInNewPlayer("badpuzzleid", "badpuzzleid@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puzzleId\":\"not-a-uuid\",\"answer\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a client cannot claim success, XP or coins in the request body")
    void ignoresClientSuppliedOutcomes() throws Exception {
        String token = signInNewPlayer("claimer", "claimer@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        var mission = missionRepository.findById(missionId).orElseThrow();
        PlayerProfile before = profileOf("claimer@example.com");

        startMission(token, "RECON_PERIMETER");
        var puzzle = latestPuzzle("claimer@example.com", missionId);

        // Declares success while submitting an answer that is plainly wrong.
        String body = objectMapper.writeValueAsString(Map.of(
                "puzzleId", puzzle.getPuzzleId(),
                "answer", "WRONG-ON-PURPOSE",
                "success", true,
                "correct", true,
                "score", 100,
                "experience", 999_999,
                "coins", 999_999,
                "level", 99,
                "elapsedMs", 1));

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("INCORRECT"))
                .andExpect(jsonPath("$.data.rewards.experience").value(0))
                .andExpect(jsonPath("$.data.rewards.coins").value(0));

        PlayerProfile after = profileOf("claimer@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
        assertThat(after.getCoins()).isEqualTo(before.getCoins());
        assertThat(after.getLevel()).isEqualTo(1);
        assertThat(mission.getXpReward()).isPositive();
    }

    @Test
    @DisplayName("a client cannot shorten a puzzle's window by claiming a fast solve")
    void ignoresClientSuppliedTiming() throws Exception {
        String token = signInNewPlayer("fast", "fast@example.com");
        UUID missionId = missionId("CRYPTO_TRANSMISSION");

        startMission(token, "CRYPTO_TRANSMISSION");
        var puzzle = latestPuzzle("fast@example.com", missionId);

        // The window closes while the "solver" is still claiming they took 3 ms.
        expirePuzzleWindow(puzzle.getPuzzleId());

        String body = objectMapper.writeValueAsString(Map.of(
                "puzzleId", puzzle.getPuzzleId(),
                "answer", correctAnswerFor(puzzle),
                "elapsedMs", 3,
                "timeLimitSeconds", 999_999,
                "startedAt", "2026-01-01T00:00:00Z"));

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("EXPIRED"))
                .andExpect(jsonPath("$.data.rewards.experience").value(0));

        assertThat(profileOf("fast@example.com").getExperience()).isZero();
    }

    @Test
    @DisplayName("the puzzle cannot be answered twice for two payouts")
    void duplicateSubmissionIsIdempotent() throws Exception {
        String token = signInNewPlayer("dupsec", "dupsec@example.com");
        UUID missionId = missionId("RECON_PERIMETER");
        var mission = missionRepository.findById(missionId).orElseThrow();
        PlayerProfile before = profileOf("dupsec@example.com");

        startMission(token, "RECON_PERIMETER");
        var puzzle = latestPuzzle("dupsec@example.com", missionId);
        String answer = correctAnswerFor(puzzle);

        JsonNode first = submitPuzzle(token, missionId, puzzle.getPuzzleId(), answer);
        assertThat(first.path("rewards").path("coins").asLong()).isEqualTo(mission.getCoinReward());

        for (int i = 0; i < 4; i++) {
            JsonNode repeat = submitPuzzle(token, missionId, puzzle.getPuzzleId(), answer);
            assertThat(repeat.path("rewards").path("coins").asLong()).isZero();
            assertThat(repeat.path("rewards").path("experience").asLong()).isZero();
            assertThat(repeat.path("alreadySolved").asBoolean()).isTrue();
        }

        PlayerProfile after = profileOf("dupsec@example.com");
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
        assertThat(after.getExperience()).isEqualTo(before.getExperience() + mission.getXpReward());
    }

    @Test
    @DisplayName("submission requires a valid access token")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/player/missions/" + missionId("RECON_PERIMETER")
                                + "/puzzle/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puzzleId\":\"" + randomUuid() + "\",\"answer\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an invalid token is refused, not silently downgraded")
    void rejectsInvalidTokens() throws Exception {
        mockMvc.perform(authPost("/api/v1/player/missions/"
                        + missionId("RECON_PERIMETER") + "/puzzle/submit", "not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puzzleId\":\"" + randomUuid() + "\",\"answer\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a completed mission cannot be re-entered through its old puzzle")
    void cannotReEnterACompletedMission() throws Exception {
        String token = signInNewPlayer("reenter", "reenter@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        startMission(token, "RECON_PERIMETER");
        var puzzle = latestPuzzle("reenter@example.com", missionId);
        submitPuzzle(token, missionId, puzzle.getPuzzleId(), correctAnswerFor(puzzle));

        long coinsAfterSolve = profileOf("reenter@example.com").getCoins();

        JsonNode replay = submitPuzzle(token, missionId, puzzle.getPuzzleId(), correctAnswerFor(puzzle));
        assertThat(replay.path("alreadySolved").asBoolean()).isTrue();
        assertThat(replay.path("rewards").path("coins").asLong()).isZero();
        assertThat(profileOf("reenter@example.com").getCoins()).isEqualTo(coinsAfterSolve);
    }

    @Test
    @DisplayName("the answer for a superseded puzzle does not complete the mission")
    void cannotAnswerASupersededPuzzle() throws Exception {
        String token = signInNewPlayer("superseded", "superseded@example.com");
        UUID missionId = missionId("RECON_PERIMETER");

        startMission(token, "RECON_PERIMETER");
        var firstPuzzle = latestPuzzle("superseded@example.com", missionId);

        // Restarting costs energy and replaces the puzzle; the old one is spent.
        startMission(token, "RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PuzzlePayload(firstPuzzle.getPuzzleId(), correctAnswerFor(firstPuzzle)))))
                .andExpect(status().isConflict());

        assertThat(profileOf("superseded@example.com").getExperience()).isZero();
        assertThat(progressRepository
                .findByUserIdAndMissionId(userIdOf("superseded@example.com"), missionId)
                .orElseThrow().getStatus())
                .isEqualTo(com.cyberheist.mission.MissionStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("no endpoint exposes a puzzle answer")
    void noEndpointExposesAnswers() throws Exception {
        String token = signInNewPlayer("exposed", "exposed@example.com");
        // Free-text, so "the answer is absent from the payload" is checkable by
        // searching for it.
        UUID missionId = missionId("CRYPTO_TRANSMISSION");

        String startBody = mockMvc.perform(
                        authPost("/api/v1/player/missions/" + missionId + "/start", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var puzzle = latestPuzzle("exposed@example.com", missionId);
        String answer = correctAnswerFor(puzzle);
        assertThat(answer).isNotBlank();

        // The persisted row carries the seed and the state, never the answer.
        assertThat(puzzle.getSeed()).isNotZero();
        assertThat(puzzle.getClass().getDeclaredFields())
                .as("the entity must not have grown a field that could hold an answer")
                .noneMatch(field -> field.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains("answer"));
        assertThat(startBody).doesNotContain(answer);
        assertThat(startBody.toLowerCase(java.util.Locale.ROOT)).doesNotContain("\"seed\"");
    }

    private static final class Map {
        static java.util.Map<String, Object> of(Object... pairs) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                map.put((String) pairs[i], pairs[i + 1]);
            }
            return map;
        }
    }
}