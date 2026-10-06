package com.cyberheist.mission;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.player.PlayerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the mission catalogue against a level-gating deadlock.
 *
 * <p>The seed once gated its ELITE mission behind a level that could never be
 * reached from the available XP, making part of the catalogue unobtainable.
 * This test plays the catalogue in level order and fails if any mission ever
 * becomes unreachable.
 *
 * <p>Every mission is played through the full Phase 3 loop - start, solve,
 * submit - so the guard also proves that a correct answer is reachable for all
 * five puzzle families rather than only for the ones a player happens to see
 * first.
 *
 * <p>Energy is topped up by the test itself. Regeneration exists now, but it
 * works in real minutes and this test must stay about the level gate, so
 * topping up keeps the two concerns apart.
 */
class MissionProgressionIntegrationTest extends IntegrationTestSupport {

    private static final String GRINDER_EMAIL = "progressgrinder@example.com";

    @Test
    @DisplayName("a player who plays in level order can reach and finish every mission")
    void everyMissionIsReachableByLevel() throws Exception {
        // Distinct username: tests share one in-memory database per JVM.
        String token = signInNewPlayer("progressgrinder", GRINDER_EMAIL);
        UUID grinderId = userIdOf(GRINDER_EMAIL);

        List<String> played = new ArrayList<>();

        // Generous loop guard: far more than the 15 seeded missions.
        for (int attempt = 0; attempt < 50; attempt++) {
            List<String> startable = findStartableCodes(token);
            if (startable.isEmpty()) {
                break;
            }

            // Cheapest available mission first, which is how a player climbs.
            String code = startable.get(0);
            played.add(code);
            topUpEnergy(grinderId);
            startAndSolve(token, code);
        }

        assertThat(findStartableCodes(token))
                .as("no mission should remain unstartable after playing the catalogue")
                .isEmpty();
        assertThat(completedCodes(token))
                .as("every seeded mission must be completable")
                .hasSize(15);
        assertThat(played)
                .as("no mission may be started twice")
                .hasSize(15)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("no mission is gated above the level its own rewards cannot reach")
    void noMissionIsGatedOutOfRange() {
        long totalXp = missionRepository.findAll().stream()
                .mapToLong(Mission::getXpReward)
                .sum();

        missionRepository.findAll().forEach(mission -> {
            // A player must be able to start this mission using XP earned from
            // every OTHER mission, so the mission's own payout cannot count.
            long xpEarnableWithoutIt = totalXp - mission.getXpReward();
            int reachableLevel = levelCurve.levelFor(xpEarnableWithoutIt);

            assertThat(mission.getRequiredLevel())
                    .as("%s requires level %d, but other missions only reach level %d",
                            mission.getCode(), mission.getRequiredLevel(), reachableLevel)
                    .isLessThanOrEqualTo(reachableLevel);
        });
    }
    private List<String> findStartableCodes(String token) throws Exception {
        String body = mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var missions = objectMapper.readTree(body).path("data");
        List<String> codes = new ArrayList<>();
        missions.forEach(mission -> {
            if (mission.path("startable").asBoolean(false)) {
                codes.add(mission.path("code").asText());
            }
        });
        return codes;
    }

    private List<String> completedCodes(String token) throws Exception {
        String body = mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var missions = objectMapper.readTree(body).path("data");
        List<String> codes = new ArrayList<>();
        missions.forEach(mission -> {
            if ("COMPLETED".equals(mission.path("status").asText())) {
                codes.add(mission.path("code").asText());
            }
        });
        return codes;
    }

    private void startAndSolve(String token, String code) throws Exception {
        UUID id = missionId(code);

        mockMvc.perform(authPost("/api/v1/player/missions/" + id + "/start", token))
                .andExpect(status().isOk());

        var puzzle = latestPuzzle(GRINDER_EMAIL, id);
        submitPuzzle(token, id, puzzle.getPuzzleId(), correctAnswerFor(puzzle))
                .path("outcome");
        mockMvc.perform(authGet("/api/v1/player/missions/" + id, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    /**
     * Refills energy when it runs low.
     *
     * <p>{@link PlayerProfile} deliberately exposes no public setter for energy,
     * because production code must go through {@code spendEnergy}. The test
     * writes the field reflectively rather than opening a hole in the entity.
     */
    private void topUpEnergy(UUID userId) {
        var profile = profileRepository.findByUserId(userId).orElseThrow();
        if (profile.getEnergy() >= 50) {
            return;
        }

        try {
            Field field = findField(profile.getClass(), "energy");
            field.set(profile, 100);
            profileRepository.saveAndFlush(profile);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Unable to top up energy in test", ex);
        }
    }

    private Field findField(Class<?> type, String name) {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("Field not found: " + name);
    }
}