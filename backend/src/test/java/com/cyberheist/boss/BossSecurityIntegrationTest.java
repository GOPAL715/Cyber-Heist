package com.cyberheist.boss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Boss security.
 *
 * <p>Every case here is a thing a client might try. The submission DTO has two
 * fields, so most of these attacks cannot even be expressed — the point of the
 * tests is to prove that, not merely that the server ignores them.
 */
class BossSecurityIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("ignores a start body that claims a cheaper energy cost")
    void ignoresStartCostTampering() throws Exception {
        String email = "sec_cost@example.com";
        String token = signInNewPlayer("sec_cost", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("THE_FIREWALL").orElseThrow();

        int energyBefore = profileOf(email).getEnergy();

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/bosses/" + boss.getId() + "/start", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"energyCost\":1,\"stage\":3,\"difficulty\":\"EASY\",\"reward\":99999}"))
                .andExpect(status().isOk())
                .andReturn();

        // Charged the catalogue price, opened at stage 1, at the boss's own tier.
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("currentStage").asInt()).isEqualTo(1);
        assertThat(data.path("bossDifficulty").asText()).isEqualTo("MEDIUM");
        assertThat(profileOf(email).getEnergy()).isEqualTo(energyBefore - boss.getEnergyCost());
    }

    @Test
    @DisplayName("ignores a submission that claims its own damage and integrity")
    void ignoresDamageAndIntegrityTampering() throws Exception {
        String email = "sec_damage@example.com";
        String token = signInNewPlayer("sec_damage", email);
        levelUpTo(email, 6);

        JsonNode started = startBoss(token, bossId("THE_FIREWALL"));
        UUID puzzleId = UUID.fromString(started.path("puzzle").path("puzzleId").asText());
        String answer = correctBossAnswer(token, email);

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new ForgedPayload(
                                        puzzleId, answer, 999999, 0, 3, 100000L, 100000L))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        // Damage came from boss_stages (20), so integrity is 80 not 0.
        assertThat(data.path("bossIntegrity").asInt()).isEqualTo(80);
        assertThat(data.path("currentStage").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("cannot claim victory by replaying the final phase")
    void cannotClaimVictory() throws Exception {
        String email = "sec_victory@example.com";
        String token = signInNewPlayer("sec_victory", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("ZERO_DAY").orElseThrow();

        startBoss(token, bossId("ZERO_DAY"));
        long coinsBefore = profileOf(email).getCoins();

        // Win it honestly first, so the replay below reuses the real final
        // puzzle id rather than a guessed one.
        defeatBoss(token, email, boss.getStageCount());
        UUID finalPuzzle = lastPuzzleId(email);

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(finalPuzzle, "x"))))
                .andReturn();

        // No live encounter remains, so the replay pays nothing a second time.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(profileOf(email).getCoins()).isEqualTo(coinsBefore + boss.getCoinReward());
    }

    @Test
    @DisplayName("cannot submit another player's puzzle")
    void cannotSubmitAnotherPlayersPuzzle() throws Exception {
        String emailA = "sec_puzz_a@example.com";
        String tokenA = signInNewPlayer("sec_puzz_a", emailA);
        levelUpTo(emailA, 6);
        JsonNode started = startBoss(tokenA, bossId("THE_FIREWALL"));
        UUID puzzleA = UUID.fromString(started.path("puzzle").path("puzzleId").asText());

        String emailB = "sec_puzz_b@example.com";
        registerPlayer("sec_puzz_b", emailB);
        String tokenB = loginAndGetAccessToken(emailB, VALID_PASSWORD);
        levelUpTo(emailB, 6);
        startBoss(tokenB, bossId("THE_FIREWALL"));

        // B tries A's puzzle id against B's own encounter.
        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(puzzleA, "anything"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        // A's encounter is untouched.
        assertThat(currentEncounter(tokenA).path("currentStage").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("cannot use a mission puzzle to advance a boss")
    void cannotUseMissionPuzzleForBoss() throws Exception {
        String email = "sec_cross@example.com";
        String token = signInNewPlayer("sec_cross", email);
        levelUpTo(email, 6);

        startBoss(token, bossId("THE_FIREWALL"));
        // Generate a mission puzzle for the same player.
        startMission(token, "RECON_PERIMETER");
        UUID missionPuzzle = latestPuzzle(email, missionId("RECON_PERIMETER")).getPuzzleId();

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(missionPuzzle, "x"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("cannot use a boss puzzle to advance a mission")
    void cannotUseBossPuzzleForMission() throws Exception {
        String email = "sec_cross2@example.com";
        String token = signInNewPlayer("sec_cross2", email);
        levelUpTo(email, 6);

        JsonNode started = startBoss(token, bossId("THE_FIREWALL"));
        UUID bossPuzzle = UUID.fromString(started.path("puzzle").path("puzzleId").asText());
        UUID mission = missionId("RECON_PERIMETER");
        startMission(token, "RECON_PERIMETER");

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/missions/" + mission + "/puzzle/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new PuzzlePayload(bossPuzzle, "x"))))
                .andReturn();

        // Identical to submitting an id that does not exist: the ownership
        // check is the same and the message does not confirm the id is real.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("cannot start or read another player's encounter")
    void cannotReachAnotherPlayersEncounter() throws Exception {
        String emailA = "sec_owner_a@example.com";
        String tokenA = signInNewPlayer("sec_owner_a", emailA);
        levelUpTo(emailA, 6);
        startBoss(tokenA, bossId("THE_FIREWALL"));

        String emailB = "sec_owner_b@example.com";
        registerPlayer("sec_owner_b", emailB);
        String tokenB = loginAndGetAccessToken(emailB, VALID_PASSWORD);

        // B's own view is empty, not A's.
        mockMvc.perform(authGet("/api/v1/player/boss/encounter", tokenB))
                .andExpect(status().isNotFound());

        JsonNode history = getData(tokenB, "/api/v1/player/bosses/history");
        assertThat(history).isEmpty();
    }

    @Test
    @DisplayName("history is bounded and contains only the caller's encounters")
    void historyIsBoundedAndScoped() throws Exception {
        String email = "sec_history@example.com";
        String token = signInNewPlayer("sec_history", email);
        levelUpTo(email, 6);
        registerPlayer("sec_history_b", "sec_history_b@example.com");

        // Win three fights to build some history.
        for (int i = 0; i < 3; i++) {
            Boss boss = bossRepository.findByCode("ZERO_DAY").orElseThrow();
            startBoss(token, bossId("ZERO_DAY"));
            defeatBoss(token, email, boss.getStageCount());
            // Each victory sets a cooldown, so re-entering needs the clock moved.
            clearCooldown(email);
        }

        JsonNode history = getData(token, "/api/v1/player/bosses/history");
        assertThat(history).hasSize(3);
        assertThat(history.get(0).path("status").asText()).isEqualTo("VICTORY");
        assertThat(history.get(0).path("bossCode").asText()).isEqualTo("ZERO_DAY");
        assertThat(history.get(0).path("rewards").path("coins").asLong()).isEqualTo(150);

        // Another player sees none of it.
        JsonNode other = getData(
                loginAndGetAccessToken("sec_history_b@example.com", VALID_PASSWORD),
                "/api/v1/player/bosses/history");
        assertThat(other).isEmpty();
    }

    @Test
    @DisplayName("one player's fight never moves another player's balance")
    void fightsAreIsolated() throws Exception {
        String emailA = "sec_iso_a@example.com";
        String tokenA = signInNewPlayer("sec_iso_a", emailA);
        levelUpTo(emailA, 6);
        String emailB = "sec_iso_b@example.com";
        registerPlayer("sec_iso_b", emailB);
        levelUpTo(emailB, 6);

        PlayerProfile before = profileOf(emailB);

        Boss boss = bossRepository.findByCode("ZERO_DAY").orElseThrow();
        startBoss(tokenA, bossId("ZERO_DAY"));
        defeatBoss(tokenA, emailA, boss.getStageCount());

        PlayerProfile after = profileOf(emailB);
        assertThat(after.getCoins()).isEqualTo(before.getCoins());
        assertThat(after.getExperience()).isEqualTo(before.getExperience());
        assertThat(after.getEnergy()).isEqualTo(before.getEnergy());
    }

    /**
     * Moves every cooldown into the past so a retry is possible in-test.
     *
     * <p>Each row is explicitly {@code save}d: the test method is not
     * transactional, so the entities the repository returned are detached and a
     * bare flush would persist nothing.
     */
    private void clearCooldown(String email) {
        for (BossEncounter row : bossEncounterRepository.findByUserId(userIdOf(email))) {
            try {
                java.lang.reflect.Field field =
                        BossEncounter.class.getDeclaredField("cooldownUntil");
                field.setAccessible(true);
                field.set(row, java.time.Instant.now().minusSeconds(60));
                bossEncounterRepository.save(row);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to clear a cooldown in a test", ex);
            }
        }
        bossEncounterRepository.flush();
    }

    private UUID lastPuzzleId(String email) {
        return puzzleRepository
                .findByBossEncounterIdOrderByAttemptNumberAsc(
                        bossEncounterRepository.findFirstByUserIdOrderByCreatedAtDesc(userIdOf(email))
                                .orElseThrow().getId())
                .get(2)
                .getPuzzleId();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
    }

    /** A submission carrying every field a forger might hope to influence. */
    public record ForgedPayload(
            UUID puzzleId,
            String answer,
            int damage,
            int bossIntegrity,
            int stage,
            long xp,
            long coins
    ) {
    }
}