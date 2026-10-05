package com.cyberheist.mission;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Completing a mission: rewards, progression and idempotency. */
class MissionCompleteIntegrationTest extends IntegrationTestSupport {

    /** Starts a mission and returns its id. */
    private UUID startFirstMission(String token, String code) throws Exception {
        UUID id = missionId(code);
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/start", token))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    @DisplayName("completing awards the mission's server-side XP and coins")
    void awardsRewards() throws Exception {
        String token = signInNewPlayer("completer", "completer@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("completer@example.com");

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mission.code").value("RECON_PERIMETER"))
                .andExpect(jsonPath("$.data.rewards.experience").value(mission.getXpReward()))
                .andExpect(jsonPath("$.data.rewards.coins").value(mission.getCoinReward()))
                .andExpect(jsonPath("$.data.alreadyCompleted").value(false));

        PlayerProfile after = profileOf("completer@example.com");
        assertThat(after.getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("completion marks the mission COMPLETED with a timestamp")
    void marksProgressCompleted() throws Exception {
        String token = signInNewPlayer("completer2", "completer2@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        UUID userId = userRepository.findByEmailIgnoreCase("completer2@example.com")
                .orElseThrow().getId();

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk());

        var progress = progressRepository.findByUserIdAndMissionId(userId, id).orElseThrow();
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
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk());

        assertThat(profileOf("energykeeper@example.com").getEnergy()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the completion response reports the resulting progression")
    void reportsProgression() throws Exception {
        String token = signInNewPlayer("progressor", "progressor@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progression.levelBefore").value(1))
                // 50 XP does not cross the 100 XP threshold for level 2.
                .andExpect(jsonPath("$.data.progression.levelAfter").value(1))
                .andExpect(jsonPath("$.data.progression.leveledUp").value(false))
                .andExpect(jsonPath("$.data.progression.experience").value(50))
                .andExpect(jsonPath("$.data.player.level").value(1))
                .andExpect(jsonPath("$.data.player.experience").value(50));
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

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progression.levelBefore").value(1))
                .andExpect(jsonPath("$.data.progression.levelAfter").value(2))
                .andExpect(jsonPath("$.data.progression.leveledUp").value(true))
                // XP is cumulative: 110, not 10.
                .andExpect(jsonPath("$.data.progression.experience").value(110))
                .andExpect(jsonPath("$.data.progression.levelsGained").value(1));

        PlayerProfile after = profileOf("leveler@example.com");
        assertThat(after.getLevel()).isEqualTo(2);
        assertThat(after.getExperience()).isEqualTo(110);
    }
    @Test
    @DisplayName("completing twice awards rewards exactly once")
    void duplicateCompletionAwardsOnce() throws Exception {
        String token = signInNewPlayer("dupe", "dupe@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("dupe@example.com");

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk());

        // A second, identical request is reported without paying again.
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyCompleted").value(true))
                .andExpect(jsonPath("$.data.rewards.experience").value(0))
                .andExpect(jsonPath("$.data.rewards.coins").value(0));

        PlayerProfile after = profileOf("dupe@example.com");
        assertThat(after.getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }

    @Test
    @DisplayName("many duplicate completions still only pay once")
    void repeatedCompletionsPayOnce() throws Exception {
        String token = signInNewPlayer("spam", "spam@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("spam@example.com");

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.alreadyCompleted").value(true));
        }

        PlayerProfile after = profileOf("spam@example.com");
        assertThat(after.getExperience())
                .isEqualTo(before.getExperience() + mission.getXpReward());
        assertThat(after.getCoins()).isEqualTo(before.getCoins() + mission.getCoinReward());
    }
    @Test
    @DisplayName("concurrent completions pay exactly once")
    void concurrentCompletionsPayOnce() throws Exception {
        String token = signInNewPlayer("racer", "racer@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("racer@example.com");

        int threads = 8;
        var startLine = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startLine.await();
                        var result = mockMvc.perform(
                                        authPost("/api/v1/player/missions/" + id + "/complete", token))
                                .andReturn();
                        results.add(result.getResponse().getStatus());
                    } catch (Exception ex) {
                        results.add(-1);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        // Every request is answered safely: no 500s from lock contention.
        assertThat(results).allMatch(status -> status == 200);

        PlayerProfile after = profileOf("racer@example.com");
        assertThat(after.getExperience())
                .as("XP must be awarded exactly once despite %d concurrent requests", threads)
                .isEqualTo(before.getExperience() + mission.getXpReward());
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
    @DisplayName("completion requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/player/missions/" + missionId("RECON_PERIMETER") + "/complete"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @DisplayName("a client cannot inflate rewards by sending them in the body")
    void ignoresClientSuppliedRewards() throws Exception {
        String token = signInNewPlayer("cheater", "cheater@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");
        Mission mission = missionRepository.findById(id).orElseThrow();
        PlayerProfile before = profileOf("cheater@example.com");

        // The endpoint takes no body; a client that sends one must not change the payout.
        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"experience\":999999,\"coins\":999999,\"xpReward\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewards.experience").value(mission.getXpReward()));

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
            mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                    .andExpect(status().isOk());
            expected += missionRepository.findById(id).orElseThrow().getCoinReward();
        }

        assertThat(profileOf("saver@example.com").getCoins()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the profile endpoint reflects progression after a completion")
    void profileReflectsReward() throws Exception {
        String token = signInNewPlayer("reflect", "reflect@example.com");
        UUID id = startFirstMission(token, "RECON_PERIMETER");

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                .andExpect(status().isOk());

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
            mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/complete", token))
                    .andExpect(status().isOk());
        }

        PlayerProfile after = profileOf("grinder@example.com");
        assertThat(after.getExperience()).isEqualTo(165);
        assertThat(after.getLevel()).isEqualTo(2);
    }
}