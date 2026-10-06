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
 * The encounter lifecycle: start, stage by stage, win or lose.
 *
 * <p>The recurring themes are that a boss is a commitment (entry energy is spent
 * once and never refunded), that integrity and damage are the server's to move,
 * and that losing pays nothing.
 */
class BossEncounterIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("starting a boss charges energy once and opens the encounter")
    void startsEncounter() throws Exception {
        String email = "enc_start@example.com";
        String token = signInNewPlayer("enc_start", email);
        levelUpTo(email, 6);

        int energyBefore = profileOf(email).getEnergy();
        int cost = bossRepository.findByCode("THE_FIREWALL").orElseThrow().getEnergyCost();

        JsonNode state = startBoss(token, bossId("THE_FIREWALL"));

        assertThat(state.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(state.path("currentStage").asInt()).isEqualTo(1);
        assertThat(state.path("stageCount").asInt()).isEqualTo(3);
        assertThat(state.path("bossIntegrity").asInt()).isEqualTo(100);
        assertThat(state.path("bossIntegrityPercent").asInt()).isEqualTo(100);

        // Charged once, at the catalogue price.
        assertThat(profileOf(email).getEnergy()).isEqualTo(energyBefore - cost);
    }

    @Test
    @DisplayName("issues the first phase's puzzle without its answer")
    void issuesFirstPuzzle() throws Exception {
        String email = "enc_puzzle@example.com";
        String token = signInNewPlayer("enc_puzzle", email);
        levelUpTo(email, 6);

        JsonNode state = startBoss(token, bossId("THE_FIREWALL"));

        JsonNode puzzle = state.path("puzzle");
        assertThat(puzzle.path("puzzleId").asText()).isNotBlank();
        assertThat(puzzle.path("type").asText()).isEqualTo("PATTERN");
        // The answer is never sent, exactly as for a mission.
        assertThat(puzzle.has("answer")).isFalse();
        assertThat(puzzle.has("expectedAnswer")).isFalse();
        assertThat(state.path("stageName").asText()).isEqualTo("Scan the Perimeter");
    }

    @Test
    @DisplayName("a correct answer reduces integrity and issues the next phase")
    void clearsStageAndAdvances() throws Exception {
        String email = "enc_stage@example.com";
        String token = signInNewPlayer("enc_stage", email);
        levelUpTo(email, 6);
        startBoss(token, bossId("THE_FIREWALL"));

        JsonNode next = submitBossStage(token, email, correctBossAnswer(token, email));

        // THE_FIREWALL stage 1 deals 20.
        assertThat(next.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(next.path("currentStage").asInt()).isEqualTo(2);
        assertThat(next.path("bossIntegrity").asInt()).isEqualTo(80);
        assertThat(next.path("bossIntegrityPercent").asInt()).isEqualTo(80);
        // Stage 2 is the cipher phase.
        assertThat(next.path("puzzle").path("type").asText()).isEqualTo("CIPHER");
        assertThat(next.path("stageName").asText()).isEqualTo("Break the Cipher");
    }

    @Test
    @DisplayName("every phase clears and the final one defeats the boss")
    void defeatsBossOnFinalStage() throws Exception {
        String email = "enc_win@example.com";
        String token = signInNewPlayer("enc_win", email);
        levelUpTo(email, 6);
        startBoss(token, bossId("THE_FIREWALL"));

        JsonNode stage2 = submitBossStage(token, email, correctBossAnswer(token, email));
        assertThat(stage2.path("currentStage").asInt()).isEqualTo(2);
        assertThat(stage2.path("bossIntegrity").asInt()).isEqualTo(80);

        JsonNode stage3 = submitBossStage(token, email, correctBossAnswer(token, email));
        assertThat(stage3.path("currentStage").asInt()).isEqualTo(3);
        assertThat(stage3.path("bossIntegrity").asInt()).isEqualTo(50);

        JsonNode victory = submitBossStage(token, email, correctBossAnswer(token, email));

        assertThat(victory.path("status").asText()).isEqualTo("VICTORY");
        assertThat(victory.path("bossIntegrity").asInt()).isZero();
        assertThat(victory.path("reachedStage").asInt()).isEqualTo(3);
        assertThat(victory.path("rewards").path("experience").asLong()).isEqualTo(350);
        assertThat(victory.path("rewards").path("coins").asLong()).isEqualTo(220);
        // No puzzle on a finished encounter.
        assertThat(victory.has("puzzle")).isFalse();
    }

    @Test
    @DisplayName("pays the boss reward into the profile")
    void paysOnVictory() throws Exception {
        String email = "enc_pay@example.com";
        String token = signInNewPlayer("enc_pay", email);
        levelUpTo(email, 6);

        long coinsBefore = profileOf(email).getCoins();
        long xpBefore = profileOf(email).getExperience();
        Boss boss = bossRepository.findByCode("ZERO_DAY").orElseThrow();

        startBoss(token, bossId("ZERO_DAY"));
        JsonNode victory = defeatBoss(token, email, boss.getStageCount());

        assertThat(victory.path("status").asText()).isEqualTo("VICTORY");
        PlayerProfile after = profileOf(email);
        assertThat(after.getCoins() - coinsBefore).isEqualTo(boss.getCoinReward());
        assertThat(after.getExperience() - xpBefore).isEqualTo(boss.getXpReward());
    }

    @Test
    @DisplayName("a level-up during a boss reward grants a skill point")
    void grantsSkillPointOnLevelUp() throws Exception {
        String email = "enc_skillpoint@example.com";
        String token = signInNewPlayer("enc_skillpoint", email);

        // Sit 10 XP short of level 7, so a 350 XP boss reward definitely crosses
        // a boundary. Level 20 would not: the curve is steep enough there that
        // 1,200 XP is not even one level.
        levelUpTo(email, 6);
        long shortOfSeven = levelCurve.xpRequiredFor(7) - 10;
        applyExperience(email, shortOfSeven - profileOf(email).getExperience());
        assertThat(profileOf(email).getLevel()).isEqualTo(6);

        int pointsBefore = profileOf(email).getSkillPoints();

        startBoss(token, bossId("THE_FIREWALL"));
        JsonNode victory = defeatBoss(token, email,
                bossRepository.findByCode("THE_FIREWALL").orElseThrow().getStageCount());

        int gained = victory.path("progression").path("skillPointsGained").asInt();
        int levelsGained = victory.path("progression").path("levelsGained").asInt();
        assertThat(levelsGained).as("the reward must cross a level boundary").isEqualTo(1);
        assertThat(gained)
                .as("one point per level crossed, granted by ProgressionService")
                .isEqualTo(levelsGained);
        assertThat(profileOf(email).getSkillPoints()).isEqualTo(pointsBefore + gained);
    }

    @Test
    @DisplayName("a wrong answer defeats the encounter, pays nothing and refunds nothing")
    void wrongAnswerDefeats() throws Exception {
        String email = "enc_wrong@example.com";
        String token = signInNewPlayer("enc_wrong", email);
        levelUpTo(email, 6);

        long coinsBefore = profileOf(email).getCoins();
        long xpBefore = profileOf(email).getExperience();
        int energyBefore = profileOf(email).getEnergy();

        startBoss(token, bossId("THE_FIREWALL"));
        // Energy already spent on entry, so measure after that.
        energyBefore = profileOf(email).getEnergy();

        JsonNode state = submitBossStage(token, email, "definitely-not-the-answer");

        assertThat(state.path("status").asText()).isEqualTo("DEFEATED");
        assertThat(state.path("xpAwarded").asLong()).isZero();
        assertThat(state.path("coinAwarded").asLong()).isZero();
        // A losing encounter reports no rewards block at all rather than a zero one.
        assertThat(state.has("rewards")).isFalse();

        PlayerProfile after = profileOf(email);
        assertThat(after.getCoins()).isEqualTo(coinsBefore);
        assertThat(after.getExperience()).isEqualTo(xpBefore);
        // The entry energy is not refunded.
        assertThat(after.getEnergy()).isEqualTo(energyBefore);
    }

    @Test
    @DisplayName("a lost encounter cannot be resumed")
    void lostEncounterCannotResume() throws Exception {
        String email = "enc_noresume@example.com";
        String token = signInNewPlayer("enc_noresume", email);
        levelUpTo(email, 6);

        startBoss(token, bossId("THE_FIREWALL"));
        submitBossStage(token, email, "wrong");

        mockMvc.perform(authGet("/api/v1/player/boss/encounter", token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an expired phase puzzle defeats the encounter with no reward")
    void expiredPuzzleDefeats() throws Exception {
        String email = "enc_expired@example.com";
        String token = signInNewPlayer("enc_expired", email);
        levelUpTo(email, 6);

        long coinsBefore = profileOf(email).getCoins();
        startBoss(token, bossId("THE_FIREWALL"));

        expireBossPuzzleWindow(email);

        // Even the correct answer is refused once the window has closed.
        String answer = correctBossAnswer(token, email);
        JsonNode state = submitBossStage(token, email, answer);

        assertThat(state.path("status").asText()).isEqualTo("DEFEATED");
        assertThat(profileOf(email).getCoins()).isEqualTo(coinsBefore);
    }

    @Test
    @DisplayName("a lapsed encounter expires on first contact and pays nothing")
    void lapsedEncounterExpiresLazily() throws Exception {
        String email = "enc_lapse@example.com";
        String token = signInNewPlayer("enc_lapse", email);
        levelUpTo(email, 6);

        startBoss(token, bossId("THE_FIREWALL"));
        expireEncounterWindow(email);

        // Reading it is enough to resolve it: no background job involved.
        JsonNode state = currentEncounter(token);

        assertThat(state.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(state.path("xpAwarded").asLong()).isZero();
    }

    @Test
    @DisplayName("an expired encounter cannot be won even with the right answer")
    void lapsedEncounterCannotBeWon() throws Exception {
        String email = "enc_lapsewin@example.com";
        String token = signInNewPlayer("enc_lapsewin", email);
        levelUpTo(email, 6);

        startBoss(token, bossId("THE_FIREWALL"));
        String answer = correctBossAnswer(token, email);
        expireEncounterWindow(email);

        JsonNode state = submitBossStage(token, email, answer);

        assertThat(state.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(state.path("xpAwarded").asLong()).isZero();
    }

    @Test
    @DisplayName("a victory applies the boss's victory cooldown")
    void victoryAppliesCooldown() throws Exception {
        String email = "enc_cooldown@example.com";
        String token = signInNewPlayer("enc_cooldown", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("THE_FIREWALL").orElseThrow();

        startBoss(token, bossId("THE_FIREWALL"));
        JsonNode victory = defeatBoss(token, email, boss.getStageCount());

        assertThat(victory.path("status").asText()).isEqualTo("VICTORY");
        assertThat(victory.path("cooldownUntil").asText()).isNotBlank();

        // The board now reports the cooldown rather than availability.
        JsonNode item = null;
        for (JsonNode entry : getData(token, "/api/v1/player/bosses")) {
            if (boss.getCode().equals(entry.path("code").asText())) {
                item = entry;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("availability").asText()).isEqualTo("COOLDOWN");
        assertThat(item.path("canStart").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a defeat applies the shorter defeat cooldown")
    void defeatAppliesShorterCooldown() throws Exception {
        String email = "enc_defcool@example.com";
        String token = signInNewPlayer("enc_defcool", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("THE_FIREWALL").orElseThrow();

        startBoss(token, bossId("THE_FIREWALL"));
        JsonNode lost = submitBossStage(token, email, "wrong");

        // 30 minutes, not the 12 hours a victory costs.
        java.time.Instant until = java.time.Instant.parse(lost.path("cooldownUntil").asText());
        long minutes = java.time.Duration.between(java.time.Instant.now(), until).toMinutes();
        assertThat(minutes).isBetween(28L, 30L);
        assertThat(boss.getCooldownDefeatMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("starting a second boss is refused while one is live")
    void refusesSecondBossWhileActive() throws Exception {
        String email = "enc_single@example.com";
        String token = signInNewPlayer("enc_single", email);
        levelUpTo(email, 26);

        startBoss(token, bossId("THE_FIREWALL"));

        MvcResult second = startBossExpectingFailure(token, bossId("BLACK_ICE"));

        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(second.getResponse().getContentAsString()).contains("already in a boss encounter");

        // Only one encounter exists, and it is still the first one.
        assertThat(bossEncounterRepository.findByUserId(userIdOf(email)))
                .filteredOn(row -> row.getStatus() == EncounterStatus.ACTIVE)
                .hasSize(1);
    }

    @Test
    @DisplayName("starting a boss twice is refused on cooldown")
    void refusesRestartDuringCooldown() throws Exception {
        String email = "enc_recool@example.com";
        String token = signInNewPlayer("enc_recool", email);
        levelUpTo(email, 6);

        startBoss(token, bossId("THE_FIREWALL"));
        submitBossStage(token, email, "wrong");

        MvcResult again = startBossExpectingFailure(token, bossId("THE_FIREWALL"));

        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(again.getResponse().getContentAsString()).contains("cooldown");
    }

    @Test
    @DisplayName("the same player may fight a different boss after a defeat")
    void allowsDifferentBossAfterDefeat() throws Exception {
        String email = "enc_other@example.com";
        String token = signInNewPlayer("enc_other", email);
        levelUpTo(email, 10);

        startBoss(token, bossId("THE_FIREWALL"));
        submitBossStage(token, email, "wrong");

        // THE_PHANTOM has its own cooldown, so it is unaffected.
        JsonNode next = startBoss(token, bossId("THE_PHANTOM"));
        assertThat(next.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(next.path("bossCode").asText()).isEqualTo("THE_PHANTOM");
    }

    @Test
    @DisplayName("refuses to start below the required level")
    void refusesBelowRequiredLevel() throws Exception {
        String token = signInNewPlayer("enc_lowlevel", "enc_lowlevel@example.com");

        MvcResult result = startBossExpectingFailure(token, bossId("THE_FIREWALL"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Requires level 6");
    }

    @Test
    @DisplayName("refuses to start without enough energy")
    void refusesWithoutEnergy() throws Exception {
        String email = "enc_noenergy@example.com";
        String token = signInNewPlayer("enc_noenergy", email);
        levelUpTo(email, 6);
        setEnergy(email, 5);

        MvcResult result = startBossExpectingFailure(token, bossId("THE_FIREWALL"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Not enough energy");
        assertThat(profileOf(email).getEnergy()).isEqualTo(5);
        assertThat(bossEncounterRepository.findByUserId(userIdOf(email))).isEmpty();
    }

    @Test
    @DisplayName("refuses an unknown boss")
    void refusesUnknownBoss() throws Exception {
        String email = "enc_unknown@example.com";
        String token = signInNewPlayer("enc_unknown", email);
        levelUpTo(email, 6);

        assertThat(startBossExpectingFailure(token, randomUuid()).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("energy efficiency reduces the charge but never below one")
    void energyEfficiencyDiscountsEntry() throws Exception {
        String email = "enc_efficiency@example.com";
        String token = signInNewPlayer("enc_efficiency", email);
        levelUpTo(email, 6);

        // Basic Firewall grants +5% energy efficiency when equipped.
        setCoins(email, 5000);
        purchase(token, itemId("BASIC_FIREWALL"));
        equip(token, com.cyberheist.shop.EquipmentSlot.SECURITY,
                ownedItem(email, "BASIC_FIREWALL").orElseThrow().getId());

        int energyBefore = profileOf(email).getEnergy();
        int base = bossRepository.findByCode("THE_FIREWALL").orElseThrow().getEnergyCost();
        int expected = com.cyberheist.shop.EquipmentBonusService.applyEnergyDiscount(base, 5);

        startBoss(token, bossId("THE_FIREWALL"));

        assertThat(energyBefore - profileOf(email).getEnergy()).isEqualTo(expected);
        assertThat(expected).isLessThanOrEqualTo(base).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("energy is charged once, not per phase")
    void chargesEnergyOnceNotPerStage() throws Exception {
        String email = "enc_once@example.com";
        String token = signInNewPlayer("enc_once", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("THE_FIREWALL").orElseThrow();

        int energyBefore = profileOf(email).getEnergy();
        startBoss(token, bossId("THE_FIREWALL"));
        int afterEntry = profileOf(email).getEnergy();

        // Clear every phase; the balance must not move again.
        defeatBoss(token, email, boss.getStageCount());

        assertThat(energyBefore - afterEntry).isEqualTo(boss.getEnergyCost());
        assertThat(profileOf(email).getEnergy()).isEqualTo(afterEntry);
    }

    @Test
    @DisplayName("a replayed final submission pays nothing a second time")
    void replayedFinalSubmissionPaysNothing() throws Exception {
        String email = "enc_replay@example.com";
        String token = signInNewPlayer("enc_replay", email);
        levelUpTo(email, 6);
        Boss boss = bossRepository.findByCode("THE_FIREWALL").orElseThrow();

        startBoss(token, bossId("THE_FIREWALL"));
        submitBossStage(token, email, correctBossAnswer(token, email));
        submitBossStage(token, email, correctBossAnswer(token, email));
        JsonNode victory = submitBossStage(token, email, correctBossAnswer(token, email));

        long coinsAfterWin = profileOf(email).getCoins();
        long xpAfterWin = profileOf(email).getExperience();

        // The player kept the puzzle id of the final phase and presses submit again.
        UUID finalPuzzle = finalStagePuzzleId(email);
        MvcResult replay = mockMvc.perform(
                        authPost("/api/v1/player/boss/encounter/stage/submit", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new BossPayload(finalPuzzle, "anything"))))
                .andReturn();

        // No live encounter remains, so the retry is a clean 404 rather than a
        // second payout.
        assertThat(replay.getResponse().getStatus()).isEqualTo(404);
        assertThat(profileOf(email).getCoins()).isEqualTo(coinsAfterWin);
        assertThat(profileOf(email).getExperience()).isEqualTo(xpAfterWin);
        assertThat(victory.path("status").asText()).isEqualTo("VICTORY");
    }

    private UUID finalStagePuzzleId(String email) {
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
}