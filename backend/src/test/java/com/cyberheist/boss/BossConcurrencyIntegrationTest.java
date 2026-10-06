package com.cyberheist.boss;

import static org.assertj.core.api.Assertions.assertThat;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.puzzle.PuzzleAttempt;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Concurrent boss operations.
 *
 * <p>Five races, each of which would be an economy bug if it went the other
 * way: two starts, two submissions of the same phase, two final submissions, an
 * expiry racing a submission, and a start racing a cooldown.
 *
 * <p>All of them are held closed by the same two locks: the profile row, then the
 * encounter row, then the puzzle row, always in that order.
 */
class BossConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private BossEncounterRepository encounters;

    private ExecutorService executor;

    @AfterEach
    void shutdown() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("two simultaneous starts produce only one encounter")
    void concurrentStartsCreateOneEncounter() throws Exception {
        String email = "cc_start@example.com";
        String token = signInNewPlayer("cc_start", email);
        levelUpTo(email, 26);
        UUID firewall = bossId("THE_FIREWALL");
        UUID blackIce = bossId("BLACK_ICE");

        List<Integer> statuses = runTogether(
                () -> startBossExpectingFailure(token, firewall).getResponse().getStatus(),
                () -> startBossExpectingFailure(token, blackIce).getResponse().getStatus());

        // Both aimed at different bosses, which is exactly the case the
        // one-active-encounter rule has to catch.
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 400).hasSize(1);

        assertThat(encounters.findByUserId(userIdOf(email)))
                .filteredOn(row -> row.getStatus() == EncounterStatus.ACTIVE)
                .hasSize(1);
    }

    @Test
    @DisplayName("two simultaneous submissions consume the phase once")
    void concurrentSubmissionsConsumePhaseOnce() throws Exception {
        String email = "cc_submit@example.com";
        String token = signInNewPlayer("cc_submit", email);
        levelUpTo(email, 6);

        JsonNode started = startBoss(token, bossId("THE_FIREWALL"));
        UUID puzzleId = UUID.fromString(started.path("puzzle").path("puzzleId").asText());
        String answer = correctBossAnswer(token, email);

        List<Integer> statuses = runTogether(
                () -> submitRaw(token, puzzleId, answer),
                () -> submitRaw(token, puzzleId, answer));

        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 404).hasSize(1);

        // Exactly one phase was consumed, and the integrity moved once.
        JsonNode state = currentEncounter(token);
        assertThat(state.path("currentStage").asInt()).isEqualTo(2);
        assertThat(state.path("bossIntegrity").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("two simultaneous final submissions pay exactly once")
    void concurrentFinalSubmissionsPayOnce() throws Exception {
        String email = "cc_final@example.com";
        String token = signInNewPlayer("cc_final", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("THE_FIREWALL").orElseThrow();

        startBoss(token, bossId("THE_FIREWALL"));
        submitBossStage(token, email, correctBossAnswer(token, email));
        submitBossStage(token, email, correctBossAnswer(token, email));

        UUID finalPuzzle = puzzleRepository
                .findByBossEncounterIdOrderByAttemptNumberAsc(
                        encounters.findFirstByUserIdOrderByCreatedAtDesc(userIdOf(email))
                                .orElseThrow().getId())
                .get(2).getPuzzleId();
        String answer = correctBossAnswer(token, email);

        PlayerProfile before = profileOf(email);

        List<Integer> statuses = runTogether(
                () -> submitRaw(token, finalPuzzle, answer),
                () -> submitRaw(token, finalPuzzle, answer));

        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);

        PlayerProfile after = profileOf(email);
        // The payout happened once, not twice.
        assertThat(after.getCoins() - before.getCoins()).isEqualTo(boss.getCoinReward());
        assertThat(after.getExperience() - before.getExperience()).isEqualTo(boss.getXpReward());

        assertThat(encounters.findByUserId(userIdOf(email)))
                .filteredOn(row -> row.getStatus() == EncounterStatus.VICTORY)
                .hasSize(1);
    }

    @Test
    @DisplayName("expiry racing a submission never completes or pays")
    void expiryRacingSubmissionIsSafe() throws Exception {
        String email = "cc_expiry@example.com";
        String token = signInNewPlayer("cc_expiry", email);
        levelUpTo(email, 6);

        JsonNode started = startBoss(token, bossId("THE_FIREWALL"));
        UUID puzzleId = UUID.fromString(started.path("puzzle").path("puzzleId").asText());
        String answer = correctBossAnswer(token, email);

        // The encounter lapses while the answer is on the wire.
        expireEncounterWindow(email);

        PlayerProfile before = profileOf(email);
        List<String> bodies = runTogetherBodies(
                () -> submitRawBody(token, puzzleId, answer),
                () -> submitRawBody(token, puzzleId, answer));

        // Whichever request wins the lock resolves the expiry and reports it, so
        // a 200 here is correct behaviour. What must never happen is a win.
        assertThat(bodies).allSatisfy(body -> assertThat(body).doesNotContain("VICTORY"));

        PlayerProfile after = profileOf(email);
        assertThat(after.getCoins()).isEqualTo(before.getCoins());
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
        assertThat(encounters.findByUserId(userIdOf(email)))
                .filteredOn(row -> row.getStatus() == EncounterStatus.ACTIVE)
                .isEmpty();
    }

    @Test
    @DisplayName("a start cannot bypass an active cooldown")
    void startCannotBypassCooldown() throws Exception {
        String email = "cc_cooldown@example.com";
        String token = signInNewPlayer("cc_cooldown", email);
        levelUpTo(email, 6);
        UUID firewall = bossId("THE_FIREWALL");

        // Lose once to set a defeat cooldown.
        startBoss(token, firewall);
        submitBossStage(token, email, "wrong");

        List<Integer> statuses = runTogether(
                () -> startBossExpectingFailure(token, firewall).getResponse().getStatus(),
                () -> startBossExpectingFailure(token, firewall).getResponse().getStatus());

        assertThat(statuses).allMatch(status -> status == 400);
        assertThat(statuses).allSatisfy(status ->
                assertThat(status).as("no start should slip past the cooldown").isEqualTo(400));
    }

    @Test
    @DisplayName("a duplicated HTTP request pays once")
    void duplicatedRequestPaysOnce() throws Exception {
        String email = "cc_dup@example.com";
        String token = signInNewPlayer("cc_dup", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("ZERO_DAY").orElseThrow();

        startBoss(token, bossId("ZERO_DAY"));
        submitBossStage(token, email, correctBossAnswer(token, email));
        submitBossStage(token, email, correctBossAnswer(token, email));

        UUID finalPuzzle = puzzleRepository
                .findByBossEncounterIdOrderByAttemptNumberAsc(
                        encounters.findFirstByUserIdOrderByCreatedAtDesc(userIdOf(email))
                                .orElseThrow().getId())
                .get(2).getPuzzleId();

        // Win it first, so the retry storm below replays a *finished* fight -
        // the case that would pay twice if the state machine allowed it.
        submitBossStage(token, email, correctBossAnswer(token, email));
        PlayerProfile before = profileOf(email);

        List<Integer> statuses = runTogether(
                () -> submitRaw(token, finalPuzzle, "anything"),
                () -> submitRaw(token, finalPuzzle, "anything"));

        PlayerProfile after = profileOf(email);
        assertThat(statuses).allMatch(status -> status == 404);
        assertThat(after.getCoins() - before.getCoins())
                .as("a retry storm must pay nothing further")
                .isZero();
        assertThat(after.getExperience() - before.getExperience()).isZero();
    }

    private int submitRaw(String token, UUID puzzleId, String answer) throws Exception {
        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(puzzleId, answer))))
                .andReturn();
        return result.getResponse().getStatus();
    }

    /** Submits and returns status and body, for cases where 200 is legitimate. */
    private String submitRawBody(String token, UUID puzzleId, String answer) throws Exception {
        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(puzzleId, answer))))
                .andReturn();
        return result.getResponse().getStatus() + ":" + result.getResponse().getContentAsString();
    }

    private List<String> runTogetherBodies(Callable<String> first, Callable<String> second) throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<String> a = executor.submit(awaited(ready, go, first));
        Future<String> b = executor.submit(awaited(ready, go, second));

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }

    /** Runs both calls at once and returns their statuses. */
    private List<Integer> runTogether(Callable<Integer> first, Callable<Integer> second) throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<Integer> a = executor.submit(awaited(ready, go, first));
        Future<Integer> b = executor.submit(awaited(ready, go, second));

        assertThat(ready.await(10, TimeUnit.SECONDS)).as("both threads should start").isTrue();
        go.countDown();

        return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }

    private <T> Callable<T> awaited(CountDownLatch ready, CountDownLatch go, Callable<T> call) {
        return () -> {
            ready.countDown();
            go.await();
            return call.call();
        };
    }
}