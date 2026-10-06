package com.cyberheist.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Taking skill levels.
 *
 * <p>The recurring theme is that the request means one thing: "take this skill to
 * the next level". Several of these tests send a body full of values that would
 * be devastating if honoured, and assert none of them changed the outcome.
 */
class SkillUnlockIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("unlocks a skill, charging the level's cost")
    void unlocksSkill() throws Exception {
        String email = "unlock_ok@example.com";
        String token = signInNewPlayer("unlock_ok", email);
        setSkillPoints(email, 5);

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/skills/" + skillId("RAPID_EXECUTION") + "/unlock", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentLevel").value(1))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("code").asText()).isEqualTo("RAPID_EXECUTION");
        assertThat(data.path("cost").asInt()).isEqualTo(1);
        assertThat(data.path("balance").asInt()).isEqualTo(4);
        assertThat(data.path("effectValue").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("spends from the caller's own points only")
    void spendsOnlyCallersPoints() throws Exception {
        String tokenA = signInNewPlayer("unlock_a", "unlock_a@example.com");
        registerPlayer("unlock_b", "unlock_b@example.com");
        setSkillPoints("unlock_a@example.com", 5);
        setSkillPoints("unlock_b@example.com", 0);

        unlockSkill(tokenA, skillId("RAPID_EXECUTION"));

        assertThat(profileOf("unlock_a@example.com").getSkillPoints()).isEqualTo(4);
        assertThat(profileOf("unlock_b@example.com").getSkillPoints()).isZero();
    }

    @Test
    @DisplayName("refuses when the player cannot afford the level")
    void refusesWithoutEnoughPoints() throws Exception {
        String email = "unlock_poor@example.com";
        String token = signInNewPlayer("unlock_poor", email);
        setSkillPoints(email, 0);

        MvcResult result = unlockSkillExpectingFailure(token, skillId("RAPID_EXECUTION"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Not enough skill points");
        // Nothing was created and nothing was spent.
        assertThat(profileOf(email).getSkillPoints()).isZero();
        assertThat(findSkill(skillTree(token), "RAPID_EXECUTION").path("currentLevel").asInt()).isZero();
    }

    @Test
    @DisplayName("uses the cost from skill_levels, not a cheaper one")
    void chargesTheTableCost() throws Exception {
        String email = "unlock_cost@example.com";
        String token = signInNewPlayer("unlock_cost", email);
        // Level 3 costs 2, so exactly 2 points must leave the balance.
        setSkillPoints(email, 10);

        unlockSkill(token, skillId("RAPID_EXECUTION"));
        unlockSkill(token, skillId("RAPID_EXECUTION"));
        JsonNode third = unlockSkill(token, skillId("RAPID_EXECUTION"));

        assertThat(third.path("currentLevel").asInt()).isEqualTo(3);
        assertThat(third.path("cost").asInt()).isEqualTo(2);
        // 10 - 1 - 1 - 2
        assertThat(third.path("balance").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("refuses a skill whose prerequisite is unmet")
    void refusesWhenPrerequisiteUnmet() throws Exception {
        String email = "unlock_prereq@example.com";
        String token = signInNewPlayer("unlock_prereq", email);
        setSkillPoints(email, 10);

        MvcResult result = unlockSkillExpectingFailure(token, skillId("QUICK_RESPONSE"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Rapid Execution level 2 required");
        assertThat(profileOf(email).getSkillPoints()).isEqualTo(10);
    }

    @Test
    @DisplayName("allows a skill once its prerequisite level is reached")
    void allowsOncePrerequisiteMet() throws Exception {
        String email = "unlock_chain@example.com";
        String token = signInNewPlayer("unlock_chain", email);
        setSkillPoints(email, 10);

        // Take Rapid Execution to level 2 to satisfy Quick Response.
        unlockSkill(token, skillId("RAPID_EXECUTION"));
        unlockSkill(token, skillId("RAPID_EXECUTION"));

        JsonNode result = unlockSkill(token, skillId("QUICK_RESPONSE"));

        assertThat(result.path("currentLevel").asInt()).isEqualTo(1);
        assertThat(result.path("code").asText()).isEqualTo("QUICK_RESPONSE");
    }

    @Test
    @DisplayName("enforces a prerequisite's required level, not merely 'has it'")
    void enforcesRequiredLevel() throws Exception {
        String email = "unlock_reqlevel@example.com";
        String token = signInNewPlayer("unlock_reqlevel", email);
        setSkillPoints(email, 10);

        // Overclock needs Quick Response level 3, and Overclock is two links away.
        unlockSkill(token, skillId("RAPID_EXECUTION"));
        unlockSkill(token, skillId("RAPID_EXECUTION"));
        unlockSkill(token, skillId("QUICK_RESPONSE"));

        MvcResult result = unlockSkillExpectingFailure(token, skillId("OVERCLOCK"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Quick Response level 3 required");
    }

    @Test
    @DisplayName("refuses a skill that is already maxed")
    void refusesWhenMaxed() throws Exception {
        String email = "unlock_max@example.com";
        String token = signInNewPlayer("unlock_max", email);
        setSkillPoints(email, 50);

        for (int i = 0; i < 5; i++) {
            unlockSkill(token, skillId("RAPID_EXECUTION"));
        }
        assertThat(findSkill(skillTree(token), "RAPID_EXECUTION").path("currentLevel").asInt()).isEqualTo(5);

        MvcResult result = unlockSkillExpectingFailure(token, skillId("RAPID_EXECUTION"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("fully upgraded");
    }

    @Test
    @DisplayName("404s a skill that does not exist")
    void refusesUnknownSkill() throws Exception {
        String token = signInNewPlayer("unlock_ghost", "unlock_ghost@example.com");

        assertThat(unlockSkillExpectingFailure(token, randomUuid()).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("404s a malformed skill id rather than coercing it")
    void refusesMalformedId() throws Exception {
        String token = signInNewPlayer("unlock_badid", "unlock_badid@example.com");

        mockMvc.perform(post("/api/v1/player/skills/not-a-uuid/unlock")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ignores a body that tries to set the cost, level or effect")
    void ignoresTampering() throws Exception {
        String email = "unlock_tamper@example.com";
        String token = signInNewPlayer("unlock_tamper", email);
        setSkillPoints(email, 5);

        MvcResult result = mockMvc.perform(
                        authPost("/api/v1/player/skills/" + skillId("RAPID_EXECUTION") + "/unlock", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cost\":1,\"level\":5,\"effectValue\":999999,\"skillPoints\":9999}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        // The catalogue said level 1 costs 1 and grants 2%; the body's 999999
        // bound to nothing because the endpoint has no request DTO.
        assertThat(data.path("currentLevel").asInt()).isEqualTo(1);
        assertThat(data.path("cost").asInt()).isEqualTo(1);
        assertThat(data.path("effectValue").asInt()).isEqualTo(2);
        assertThat(profileOf(email).getSkillPoints()).isEqualTo(4);
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/player/skills/"
                        + skillId("RAPID_EXECUTION") + "/unlock"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("two players unlocking the same skill stay independent")
    void unlocksArePerPlayer() throws Exception {
        String tokenA = signInNewPlayer("unlock_iso_a", "unlock_iso_a@example.com");
        registerPlayer("unlock_iso_b", "unlock_iso_b@example.com");
        String tokenB = loginAndGetAccessToken("unlock_iso_b@example.com", VALID_PASSWORD);
        setSkillPoints("unlock_iso_a@example.com", 5);
        setSkillPoints("unlock_iso_b@example.com", 5);

        unlockSkill(tokenA, skillId("CIPHER_MASTERY"));
        unlockSkill(tokenA, skillId("CIPHER_MASTERY"));

        assertThat(findSkill(skillTree(tokenA), "CIPHER_MASTERY").path("currentLevel").asInt()).isEqualTo(2);
        assertThat(findSkill(skillTree(tokenB), "CIPHER_MASTERY").path("currentLevel").asInt()).isZero();
    }
}