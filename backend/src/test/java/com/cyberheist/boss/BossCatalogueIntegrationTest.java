package com.cyberheist.boss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the boss catalogue.
 *
 * <p>The board is the entry point to the whole system, so what matters is that
 * it reports the server's own view of what a player can and cannot do.
 */
class BossCatalogueIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("lists every active boss with its server-defined figures")
    void listsActiveBosses() throws Exception {
        String token = signInNewPlayer("boss_list", "boss_list@example.com");

        JsonNode bosses = getData(token, "/api/v1/player/bosses");

        assertThat(bosses).hasSize(5);
        JsonNode firewall = findBoss(bosses, "THE_FIREWALL");
        assertThat(firewall.path("name").asText()).isEqualTo("The Firewall");
        assertThat(firewall.path("difficulty").asText()).isEqualTo("MEDIUM");
        assertThat(firewall.path("requiredLevel").asInt()).isEqualTo(6);
        assertThat(firewall.path("energyCost").asInt()).isEqualTo(30);
        assertThat(firewall.path("stageCount").asInt()).isEqualTo(3);
        assertThat(firewall.path("xpReward").asLong()).isEqualTo(350);
        assertThat(firewall.path("coinReward").asLong()).isEqualTo(220);
    }

    @Test
    @DisplayName("exposes each boss's phases in order")
    void exposesStagesInOrder() throws Exception {
        String token = signInNewPlayer("boss_stages", "boss_stages@example.com");

        JsonNode stages = findBoss(getData(token, "/api/v1/player/bosses"), "THE_FIREWALL").path("stages");

        assertThat(stages).hasSize(3);
        assertThat(stages.get(0).path("stageNumber").asInt()).isEqualTo(1);
        assertThat(stages.get(2).path("stageNumber").asInt()).isEqualTo(3);
        // Every boss draws a different combination of existing puzzle families.
        assertThat(stages.get(0).path("puzzleType").asText()).isEqualTo("PATTERN");
        assertThat(stages.get(1).path("puzzleType").asText()).isEqualTo("CIPHER");
        assertThat(stages.get(2).path("puzzleType").asText()).isEqualTo("LOGIC");
        // Damage sums to the starting integrity, so a boss always falls.
        assertThat(stages.get(0).path("damageValue").asInt()
                + stages.get(1).path("damageValue").asInt()
                + stages.get(2).path("damageValue").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("marks a boss above the player's level as locked")
    void marksLockedBosses() throws Exception {
        String token = signInNewPlayer("boss_locked", "boss_locked@example.com");

        JsonNode bosses = getData(token, "/api/v1/player/bosses");

        // A level 1 player can reach nothing.
        for (JsonNode boss : bosses) {
            assertThat(boss.path("availability").asText()).isEqualTo("LOCKED");
            assertThat(boss.path("canStart").asBoolean()).isFalse();
            assertThat(boss.path("blockedReason").asText()).contains("Requires level");
        }
    }

    @Test
    @DisplayName("marks a boss the player has reached as available")
    void marksAvailableBosses() throws Exception {
        String email = "boss_ready@example.com";
        String token = signInNewPlayer("boss_ready", email);
        levelUpTo(email, 6);

        JsonNode firewall = findBoss(getData(token, "/api/v1/player/bosses"), "THE_FIREWALL");
        assertThat(firewall.path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(firewall.path("canStart").asBoolean()).isTrue();

        // And a higher-tier boss is still locked at the same level.
        assertThat(findBoss(getData(token, "/api/v1/player/bosses"), "THE_ARCHITECT")
                .path("availability").asText()).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("returns one boss's detail with its phases and cooldowns")
    void returnsBossDetail() throws Exception {
        String email = "boss_detail@example.com";
        String token = signInNewPlayer("boss_detail", email);
        levelUpTo(email, 10);

        mockMvc.perform(authGet("/api/v1/player/bosses/" + bossId("THE_PHANTOM"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("THE_PHANTOM"))
                .andExpect(jsonPath("$.data.requiredLevel").value(10))
                .andExpect(jsonPath("$.data.energyCost").value(40))
                .andExpect(jsonPath("$.data.cooldownVictoryMinutes").value(720))
                .andExpect(jsonPath("$.data.cooldownDefeatMinutes").value(30))
                .andExpect(jsonPath("$.data.stages.length()").value(3));
    }

    @Test
    @DisplayName("404s a boss that does not exist")
    void unknownBossIsNotFound() throws Exception {
        String token = signInNewPlayer("boss_ghost", "boss_ghost@example.com");

        mockMvc.perform(authGet("/api/v1/player/bosses/" + randomUuid(), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("hides a retired boss from the catalogue")
    void hidesRetiredBosses() throws Exception {
        String token = signInNewPlayer("boss_retired", "boss_retired@example.com");
        UUID retired = bossId("BLACK_ICE");

        withBossRetired("BLACK_ICE", () -> {
            try {
                assertThat(findBoss(getData(token, "/api/v1/player/bosses"), "BLACK_ICE")).isNull();
                mockMvc.perform(authGet("/api/v1/player/bosses/" + retired, token))
                        .andExpect(status().isNotFound());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    @DisplayName("requires authentication for every boss route")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/player/bosses")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/player/boss/encounter")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/player/bosses/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/player/bosses/" + randomUuid() + "/start"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/player/boss/encounter/stage/submit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("404s when the player has no live encounter")
    void noEncounterIsNotFound() throws Exception {
        String token = signInNewPlayer("boss_none", "boss_none@example.com");

        mockMvc.perform(authGet("/api/v1/player/boss/encounter", token))
                .andExpect(status().isNotFound());
    }

    private static JsonNode findBoss(JsonNode bosses, String code) {
        for (JsonNode boss : bosses) {
            if (code.equals(boss.path("code").asText())) {
                return boss;
            }
        }
        return null;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
    }
}