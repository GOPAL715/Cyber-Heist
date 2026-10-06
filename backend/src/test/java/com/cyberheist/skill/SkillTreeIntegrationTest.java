package com.cyberheist.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyberheist.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the skill tree.
 *
 * <p>The tree is the progression screen, so what matters is that it reports the
 * server's own view: what the player holds, what each level costs, and why
 * anything is unavailable.
 */
class SkillTreeIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("returns every branch and skill with levels and costs")
    void returnsWholeTree() throws Exception {
        String token = signInNewPlayer("tree_player", "tree@example.com");

        JsonNode tree = skillTree(token);

        // Branches come back in enum order, so the screen renders a stable set of
        // columns rather than alphabetical accident.
        assertThat(tree.path("branches")).hasSize(4);
        assertThat(tree.path("branches").get(0).path("branch").asText()).isEqualTo("SPEED");
        assertThat(tree.path("branches").get(1).path("branch").asText()).isEqualTo("INTELLIGENCE");
        assertThat(tree.path("branches").get(2).path("branch").asText()).isEqualTo("DEFENSE");
        assertThat(tree.path("branches").get(3).path("branch").asText()).isEqualTo("NETWORK");

        int total = 0;
        for (JsonNode branch : tree.path("branches")) {
            total += branch.path("skills").size();
        }
        assertThat(total).isEqualTo(12);

        JsonNode rapid = findSkill(tree, "RAPID_EXECUTION");
        assertThat(rapid.path("maxLevel").asInt()).isEqualTo(5);
        assertThat(rapid.path("levels")).hasSize(5);
        assertThat(rapid.path("currentLevel").asInt()).isZero();
    }

    @Test
    @DisplayName("a new player starts with zero skill points")
    void startsWithNoPoints() throws Exception {
        String token = signInNewPlayer("tree_fresh", "tree_fresh@example.com");

        assertThat(skillTree(token).path("skillPoints").asInt()).isZero();
    }

    @Test
    @DisplayName("reports the server-defined cost of the next level")
    void reportsNextCost() throws Exception {
        String token = signInNewPlayer("tree_cost", "tree_cost@example.com");

        JsonNode rapid = findSkill(skillTree(token), "RAPID_EXECUTION");

        assertThat(rapid.path("nextCost").asInt()).isEqualTo(1);
        assertThat(rapid.path("nextEffectType").asText()).isEqualTo("MISSION_SPEED");
        assertThat(rapid.path("nextEffectValue").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports a prerequisite and explains why a skill is locked")
    void reportsLockedReason() throws Exception {
        String token = signInNewPlayer("tree_lock", "tree_lock@example.com");

        JsonNode quick = findSkill(skillTree(token), "QUICK_RESPONSE");

        assertThat(quick.path("locked").asBoolean()).isTrue();
        assertThat(quick.path("canUnlock").asBoolean()).isFalse();
        assertThat(quick.path("blockedReason").asText()).contains("Rapid Execution");

        JsonNode prerequisites = quick.path("prerequisites");
        assertThat(prerequisites).hasSize(1);
        assertThat(prerequisites.get(0).path("code").asText()).isEqualTo("RAPID_EXECUTION");
        assertThat(prerequisites.get(0).path("requiredLevel").asInt()).isEqualTo(2);
        assertThat(prerequisites.get(0).path("currentLevel").asInt()).isZero();
    }

    @Test
    @DisplayName("the first skill of a branch is available once the player can pay")
    void rootSkillIsUnlocked() throws Exception {
        String email = "tree_root@example.com";
        String token = signInNewPlayer("tree_root", email);

        // No prerequisite, but a player with zero points still cannot take it,
        // and the screen says which of the two reasons applies.
        JsonNode broke = findSkill(skillTree(token), "RAPID_EXECUTION");
        assertThat(broke.path("locked").asBoolean()).as("no prerequisite").isFalse();
        assertThat(broke.path("canUnlock").asBoolean()).isFalse();
        assertThat(broke.path("blockedReason").asText()).isEqualTo("Not enough skill points");

        setSkillPoints(email, 1);

        JsonNode rich = findSkill(skillTree(token), "RAPID_EXECUTION");
        assertThat(rich.path("locked").asBoolean()).isFalse();
        assertThat(rich.path("canUnlock").asBoolean()).isTrue();
        // No reason to give, so the field is omitted from the body entirely.
        assertThat(rich.has("blockedReason")).isFalse();
    }

    @Test
    @DisplayName("reports the player's level after spending points")
    void reportsLevel() throws Exception {
        String email = "tree_level@example.com";
        String token = signInNewPlayer("tree_level", email);
        setSkillPoints(email, 1);

        JsonNode result = unlockSkill(token, skillId("RAPID_EXECUTION"));

        assertThat(result.path("currentLevel").asInt()).isEqualTo(1);
        assertThat(result.path("maxLevel").asInt()).isEqualTo(5);
        // Cost and remaining balance both come from the server.
        assertThat(result.path("cost").asInt()).isEqualTo(1);
        assertThat(result.path("balance").asInt()).isZero();
    }

    @Test
    @DisplayName("reports the per-source bonus breakdown")
    void reportsBonusBreakdown() throws Exception {
        String email = "tree_bonus@example.com";
        String token = signInNewPlayer("tree_bonus", email);
        setSkillPoints(email, 3);

        unlockSkill(token, skillId("SIGNAL_TRACING"));

        JsonNode bonuses = skillTree(token).path("bonuses");

        // The starter laptop is equipment, Signal Tracing is a skill: the two are
        // reported separately so the screen can attribute them.
        assertThat(bonuses.path("equipment").path("MISSION_SPEED").asInt()).isEqualTo(5);
        assertThat(bonuses.path("skills").path("COIN_BONUS").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/player/skills")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("exposes no way to set skill points directly")
    void exposesNoSkillPointMutation() throws Exception {
        String token = signInNewPlayer("tree_noput", "tree_noput@example.com");

        // PUT/PATCH on the collection would be the dangerous shape: it would
        // mean a client could simply declare a balance. 405 rather than 404
        // because the path exists and only the verb is wrong - either way the
        // request cannot mint points.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/player/skills").header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"skillPoints\":9999}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/player/skills").header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"skillPoints\":9999}"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(profileOf("tree_noput@example.com").getSkillPoints()).isZero();
    }
}