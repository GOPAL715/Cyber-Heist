package com.cyberheist.mission;

import com.cyberheist.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Browsing the mission catalogue. */
class MissionListingIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("an authenticated player can list missions")
    void listsMissions() throws Exception {
        String token = signInNewPlayer("lister", "lister@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(15));
    }

    @Test
    @DisplayName("an anonymous caller is refused")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/player/missions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("a garbage token is refused")
    void rejectsInvalidToken() throws Exception {
        mockMvc.perform(authGet("/api/v1/player/missions", "not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("missions carry the fields the client needs to render them")
    void includesDisplayFields() throws Exception {
        String token = signInNewPlayer("fields", "fields@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].code").exists())
                .andExpect(jsonPath("$.data[0].title").exists())
                .andExpect(jsonPath("$.data[0].description").exists())
                .andExpect(jsonPath("$.data[0].category").exists())
                .andExpect(jsonPath("$.data[0].difficulty").exists())
                .andExpect(jsonPath("$.data[0].requiredLevel").isNumber())
                .andExpect(jsonPath("$.data[0].xpReward").isNumber())
                .andExpect(jsonPath("$.data[0].coinReward").isNumber())
                .andExpect(jsonPath("$.data[0].energyCost").isNumber())
                .andExpect(jsonPath("$.data[0].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data[0].locked").isBoolean())
                .andExpect(jsonPath("$.data[0].startable").isBoolean());
    }

    @Test
    @DisplayName("missions above the player's level are locked and explain why")
    void locksMissionsAbovePlayerLevel() throws Exception {
        String token = signInNewPlayer("locked", "locked@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='INTEL_RECOVER_DATA')].locked")
                        .value(true))
                .andExpect(jsonPath("$.data[?(@.code=='INTEL_RECOVER_DATA')].lockReason")
                        .value("Requires level 6"))
                .andExpect(jsonPath("$.data[?(@.code=='INTEL_RECOVER_DATA')].startable")
                        .value(false));
    }

    @Test
    @DisplayName("missions within the player's level are unlocked")
    void unlocksMissionsWithinPlayerLevel() throws Exception {
        String token = signInNewPlayer("unlocked", "unlocked@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code=='RECON_PERIMETER')].locked")
                        .value(false))
                .andExpect(jsonPath("$.data[?(@.code=='RECON_PERIMETER')].startable")
                        .value(true));
    }
    @Test
    @DisplayName("the catalogue can be filtered by category")
    void filtersByCategory() throws Exception {
        String token = signInNewPlayer("filter", "filter@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions?category=CRYPTOGRAPHY", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("an unknown category filter is rejected with 400")
    void rejectsUnknownCategory() throws Exception {
        String token = signInNewPlayer("badfilter", "badfilter@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions?category=NOT_A_CATEGORY", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("mission detail returns the mission with the caller's own status")
    void returnsMissionDetail() throws Exception {
        String token = signInNewPlayer("detail", "detail@example.com");
        String id = missionId("RECON_PERIMETER").toString();

        mockMvc.perform(authGet("/api/v1/player/missions/" + id, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("RECON_PERIMETER"))
                .andExpect(jsonPath("$.data.title").value("Scan the Perimeter"))
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"));
    }

    @Test
    @DisplayName("an unknown mission id returns 404")
    void unknownMissionReturns404() throws Exception {
        String token = signInNewPlayer("nomission", "nomission@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions/" + randomUuid(), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("a malformed mission id returns 400, not a 500")
    void malformedMissionIdReturns400() throws Exception {
        String token = signInNewPlayer("badid", "badid@example.com");

        mockMvc.perform(authGet("/api/v1/player/missions/not-a-uuid", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the seeded catalogue has sane reward values")
    void seededCatalogueIsBalanced() throws Exception {
        String token = signInNewPlayer("catalogue", "catalogue@example.com");

        String body = mockMvc.perform(authGet("/api/v1/player/missions", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = objectMapper.readTree(body);
        assertThat(node.path("data")).isNotEmpty();

        node.path("data").forEach(mission -> {
            assertThat(mission.path("xpReward").asInt()).isBetween(1, 1000);
            assertThat(mission.path("coinReward").asLong()).isBetween(1L, 1000L);
            assertThat(mission.path("energyCost").asInt()).isBetween(1, 100);
            assertThat(mission.path("requiredLevel").asInt()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    @DisplayName("only active missions are listed")
    void hidesInactiveMissions() throws Exception {
        Mission mission = missionRepository.findByCode("RECON_PERIMETER").orElseThrow();
        mission.deactivate();
        missionRepository.saveAndFlush(mission);

        try {
            String token = signInNewPlayer("inactive", "inactive@example.com");

            String body = mockMvc.perform(authGet("/api/v1/player/missions", token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("RECON_PERIMETER");
            assertThat((long) objectMapper.readTree(body).path("data").size()).isEqualTo(14L);
        } finally {
            mission.activate();
            missionRepository.saveAndFlush(mission);
        }
    }
}