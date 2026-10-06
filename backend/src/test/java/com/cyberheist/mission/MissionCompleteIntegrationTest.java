package com.cyberheist.mission;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Completing a mission: rewards, progression and idempotency.
 *
 * <p>Phase 3 made the puzzle the gate on rewards, so every case here plays the
 * real loop - start, solve, submit - and asserts the same effects it always
 * asserted. The subject of these tests is the reward and idempotency machinery,
 * which has not changed; only the way a mission becomes complete has.
 */
class MissionCompleteIntegrationTest extends IntegrationTestSupport {

    /** Starts a mission and returns its id. */
    private UUID startFirstMission(String token, String code) throws Exception {
        UUID id = missionId(code);
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/start", token))
                .andExpect(status().isOk());
        return id;
    }

    /**
     * Solves the mission's live puzzle and returns the submission response.
     *
     * <p>The answer is derived by the server from the stored seed, the same way
     * {@code PuzzleService} derives it when validating a real submission.
     */
    private JsonNode solvePuzzle(String token, String email, UUID missionId) throws Exception {
        var puzzle = latestPuzzle(email, missionId);
        return submitPuzzle(token, missionId, puzzle.getPuzzleId(), correctAnswerFor(puzzle));
    }

    /** Submits a raw body to the puzzle endpoint and returns the status code. */
    private int submitRaw(String token, UUID missionId, String body) throws Exception {
        return mockMvc.perform(authPost("/api/v1/player/missions/" + missionId + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("solving a puzzle awards the mission's server-side XP and coins")
    void awardsRewards() throws Exception {
        String token = signInNewPlayer("completer", "completer@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("completer@example.com");

        JsonNode solved = solvePuzzle(token, "completer@example.com", id);

        assertThat(solved.path("outcome").asText()).isEqualTo("SOLVED");
        assertThat(solved.path("rewards").path("experience").asLong()).isEqualTo(mission.getXpReward());
        assertThat(solved.path("rewards").path("coins").asLong()).isEqualTo(mission.getCoinReward());
        assertThat(solved.path("alreadySolved").asBoolean()).isFalse();
        assertThat(solved.path("missionCompleted").asBoolean()).isTrue();

        PlayerProfile after = profileOf("completer@example.com");
        assertThat(after.getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("a wrong answer pays nothing and leaves the mission running")
    void wrongAnswerPaysNothing() throws Exception {
        String token = signInNewPlayer("wrongans", "wrongans@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("wrongans@example.com");

        JsonNode failed = submitPuzzle(token, id,
                latestPuzzle("wrongans@example.com", id).getPuzzleId(), "NOT-THE-ANSWER");

        assertThat(failed.path("outcome").asText()).isEqualTo("INCORRECT");
        assertThat(failed.path("rewards").path("experience").asLong()).isZero();
        assertThat(failed.path("rewards").path("coins").asLong()).isZero();
        assertThat(failed.path("missionCompleted").asBoolean()).isFalse();

        PlayerProfile after = profileOf("wrongans@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
        assertThat(after.getCoins()).isEqualTo(before.getCoins());

        // The mission is still running, so a retry is possible at a fresh cost.
        assertThat(progressRepository
                .findByUserIdAndMissionId(userIdOf("wrongans@example.com"), id)
                .orElseThrow().getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(mission.getXpReward()).isPositive();
    }

    @Test
    @DisplayName("completion marks the mission COMPLETED with a timestamp")
    void marksProgressCompleted() throws Exception {
        String token = signInNewPlayer("completer2", "completer2@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        solvePuzzle(token, "completer2@example.com", id);

        var progress = progressRepository.findByUserIdAndMissionId(userIdOf("completer2@example.com"), id)
                .orElseThrow();
        assertThat(progress.getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(progress.getCompletedAt()).isNotNull();
        assertThat(progress.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("energy spent at start stays spent after completion")
    void energyStaysDeducted() throws Exception {
        String token = signInNewPlayer("energykeeper", "energykeeper@example.com");
        Mission mission = missionRepository.findByCode("RECON_PERIMETER").orElseThrow();
        PlayerProfile before = profileOf("energykeeper@example.com");
        int expected = before.getEnergy() - mission.getEnergyCost();

        UUID id = startFirstMission(token, "RECON_PERIMETER");
        solvePuzzle(token, "energykeeper@example.com", id);

        assertThat(profileOf("energykeeper@example.com").getEnergy()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the completion response reports the resulting progression")
    void reportsProgression() throws Exception {
        String token = signInNewPlayer("progressor", "progressor@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        JsonNode progression = solvePuzzle(token, "progressor@example.com", id).path("progression");

        assertThat(progression.path("levelBefore").asInt()).isEqualTo(1);
        // 50 XP does not cross the 100 XP threshold for level 2.
        assertThat(progression.path("levelAfter").asInt()).isEqualTo(1);
        assertThat(progression.path("leveledUp").asBoolean()).isFalse();
        assertThat(progression.path("experience").asLong()).isEqualTo(50);
    }

    @Test
    @DisplayName("a reward that crosses the threshold levels the player up")
    void levelsUpWhenThresholdCrossed() throws Exception {
        String token = signInNewPlayer("leveler", "leveler@example.com");
        // Give the player 60 XP, then earn 50: 110 total, crossing level 2 at 100.
        PlayerProfile profile = profileOf("leveler@example.com");
        profile.addExperience(60, levelCurve);
        profileRepository.saveAndFlush(profile);

        UUID id = startFirstMission(token, "RECON_PERIMETER");
        JsonNode progression = solvePuzzle(token, "leveler@example.com", id).path("progression");

        assertThat(progression.path("levelBefore").asInt()).isEqualTo(1);
        assertThat(progression.path("levelAfter").asInt()).isEqualTo(2);
        assertThat(progression.path("leveledUp").asBoolean()).isTrue();
        // XP is cumulative: 110, not 10.
        assertThat(progression.path("experience").asLong()).isEqualTo(110);
        assertThat(progression.path("levelsGained").asInt()).isEqualTo(1);

        PlayerProfile after = profileOf("leveler@example.com");
        assertThat(after.getLevel()).isEqualTo(2);
        assertThat(after.getExperience()).isEqualTo(110);
    }

    @Test
    @DisplayName("submitting a solved puzzle twice awards rewards exactly once")
    void duplicateCompletionAwardsOnce() throws Exception {
        String token = signInNewPlayer("dupe", "dupe@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("dupe@example.com");

        var puzzle = latestPuzzle("dupe@example.com", id);
        String answer = correctAnswerFor(puzzle);
        submitPuzzle(token, id, puzzle.getPuzzleId(), answer);

        // The same puzzle, answered again: reported as solved, paid nothing.
        JsonNode replay = submitPuzzle(token, id, puzzle.getPuzzleId(), answer);
        assertThat(replay.path("alreadySolved").asBoolean()).isTrue();
        assertThat(replay.path("rewards").path("experience").asLong()).isZero();
        assertThat(replay.path("rewards").path("coins").asLong()).isZero();

        PlayerProfile after = profileOf("dupe@example.com");
        assertThat(after.getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("many duplicate submissions still only pay once")
    void repeatedCompletionsPayOnce() throws Exception {
        String token = signInNewPlayer("spam", "spam@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("spam@example.com");

        var puzzle = latestPuzzle("spam@example.com", id);
        String answer = correctAnswerFor(puzzle);
        submitPuzzle(token, id, puzzle.getPuzzleId(), answer);

        for (int i = 0; i < 5; i++) {
            JsonNode replay = submitPuzzle(token, id, puzzle.getPuzzleId(), answer);
            assertThat(replay.path("alreadySolved").asBoolean()).isTrue();
        }

        PlayerProfile after = profileOf("spam@example.com");
        assertThat(after.getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("concurrent submissions of one puzzle pay exactly once")
    void concurrentCompletionsPayOnce() throws Exception {
        String token = signInNewPlayer("racer", "racer@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("racer@example.com");

        var puzzle = latestPuzzle("racer@example.com", id);
        String body = objectMapper.writeValueAsString(
                new PuzzlePayload(puzzle.getPuzzleId(), correctAnswerFor(puzzle)));

        int threads = 8;
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();
        var executor = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startLine.await();
                        results.add(submitRaw(token, id, body));
                    } catch (Exception ex) {
                        results.add(-1);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        // Every request is answered safely: no 500s from lock contention.
        assertThat(results).hasSize(threads).allMatch(code -> code == 200);

        PlayerProfile after = profileOf("racer@example.com");
        assertThat(after.getExperience())
                .as("XP must be awarded exactly once despite %d concurrent requests", threads)
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("the legacy complete endpoint cannot pay out without a solved puzzle")
    void directCompletionAwardsNothing() throws Exception {
        String token = signInNewPlayer("bypass", "bypass@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("bypass@example.com");

        // start -> complete, the Phase 2 shortcut. It must be refused outright.
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Solve the mission's puzzle to complete it"));

        assertThat(profileOf("bypass@example.com").getExperience()).isEqualTo(before.getExperience());
        assertThat(profileOf("bypass@example.com").getCoins()).isEqualTo(before.getCoins());

        // Once the puzzle is solved the endpoint reports the completion but
        // still pays nothing, so it can never become a second payout path.
        solvePuzzle(token, "bypass@example.com", id);
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyCompleted").value(true))
                .andExpect(jsonPath("$.data.rewards.experience").value(0))
                .andExpect(jsonPath("$.data.rewards.coins").value(0));

        PlayerProfile after = profileOf("bypass@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("completing an unknown or unstarted mission is refused")
    void refusesInvalidCompletion() throws Exception {
        String token = signInNewPlayer("badcomplete", "badcomplete@example.com");

        mockMvc.perform(authPost("/api/v1/player/missions/" + randomUuid() + "/complete", token))
                .andExpect(status().isNotFound());

        mockMvc.perform(authPost(
                        "/api/v1/player/missions/" + missionId("RECON_PERIMETER") + "/complete", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("puzzle submission requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/player/missions/"
                        + missionId("RECON_PERIMETER") + "/puzzle/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puzzleId\":\"" + randomUuid() + "\",\"answer\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a client cannot inflate rewards by sending them in the body")
    void ignoresClientSuppliedRewards() throws Exception {
        String token = signInNewPlayer("cheater", "cheater@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("cheater@example.com");

        var puzzle = latestPuzzle("cheater@example.com", id);

        // The submission body binds only a puzzle id and an answer. Extra fields
        // claiming a payout reach nothing, and there is no field for them to land
        // in - including no "was I right?" flag.
        String body = objectMapper.writeValueAsString(
                new CheatingPayload(puzzle.getPuzzleId(), correctAnswerFor(puzzle),
                        true, true, 999999L, 999999L));

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/puzzle/submit", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewards.experience").value(mission.getXpReward()))
                .andExpect(jsonPath("$.data.rewards.coins").value(mission.getCoinReward()));

        PlayerProfile after = profileOf("cheater@example.com");
        assertThat(after.getExperience()).isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("coins accumulate across several missions")
    void coinsAccumulate() throws Exception {
        String token = signInNewPlayer("saver", "saver@example.com");
        PlayerProfile before = profileOf("saver@example.com");

        long expected = before.getCoins();
        for (String code : new String[]{"RECON_PERIMETER", "RECON_NETWORK_MAP", "CRYPTO_TRANSMISSION"}) {
            UUID id = startFirstMission(token, code);
            solvePuzzle(token, "saver@example.com", id);
            expected += missionRepository.findById(id).orElseThrow().getCoinReward();
        }

        assertThat(profileOf("saver@example.com").getCoins()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the profile endpoint reflects progression after a completion")
    void profileReflectsReward() throws Exception {
        String token = signInNewPlayer("reflect", "reflect@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        solvePuzzle(token, "reflect@example.com", id);

        mockMvc.perform(authGet("/api/v1/player/profile", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experience").value(50))
                .andExpect(jsonPath("$.data.level").value(1));
    }

    @Test
    @DisplayName("several missions in a row can level a player up more than once")
    void repeatedPlayKeepsProgressing() throws Exception {
        String token = signInNewPlayer("grinder", "grinder@example.com");

        // Level 1 missions total 165 XP, which crosses level 2 (100) only.
        for (String code : new String[]{"RECON_PERIMETER", "RECON_NETWORK_MAP", "CRYPTO_TRANSMISSION"}) {
            UUID id = startFirstMission(token, code);
            solvePuzzle(token, "grinder@example.com", id);
        }

        PlayerProfile after = profileOf("grinder@example.com");
        assertThat(after.getExperience()).isEqualTo(165);
        assertThat(after.getLevel()).isEqualTo(2);
    }

    /**
     * A submission body padded with the fields a cheater would add.
     *
     * <p>Jackson is configured to fail on unknown properties only where asked;
     * by default it ignores them, which is exactly the point being tested: they
     * are silently discarded rather than honoured.
     */
    private record CheatingPayload(UUID puzzleId,
                                   String answer,
                                   Boolean success,
                                   Boolean correct,
                                   Long experience,
                                   Long coins) {
    }
}