package com.cyberheist.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.cyberheist.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How skill points are earned.
 *
 * <p>{@code ProgressionService.awardExperience} is the single place a level
 * changes, so it is the single place a point is granted. These tests pin the
 * rule that matters most: one point per level <em>crossed</em>, not one per
 * reward.
 */
class SkillPointProgressionIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("a level-up grants one skill point")
    void grantsOnePointPerLevel() throws Exception {
        String email = "points_one@example.com";
        signInNewPlayer("points_one", email);
        assertThat(profileOf(email).getSkillPoints()).isZero();

        // 100 XP is exactly enough to reach level 2 from level 1.
        applyExperience(email, 100);

        assertThat(profileOf(email).getSkillPoints()).isEqualTo(1);
    }

    @Test
    @DisplayName("an award that crosses several levels grants a point for each")
    void grantsOnePointPerLevelCrossed() throws Exception {
        String email = "points_multi@example.com";
        signInNewPlayer("points_multi", email);

        // 250 total XP is level 3, so a single award crosses two thresholds.
        var result = applyExperience(email, 250);

        assertThat(result.levelsGained()).isEqualTo(2);
        assertThat(result.levelBefore()).isEqualTo(1);
        assertThat(result.levelAfter()).isEqualTo(3);
        assertThat(profileOf(email).getSkillPoints())
                .as("two levels crossed must pay two points, not one")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a large jump pays every level it crosses")
    void grantsPointsForLargeJumps() throws Exception {
        String email = "points_big@example.com";
        signInNewPlayer("points_big", email);

        // 813 total XP is level 5: four thresholds from level 1.
        var result = applyExperience(email, 813);

        assertThat(result.levelAfter()).isEqualTo(5);
        assertThat(result.levelsGained()).isEqualTo(4);
        assertThat(profileOf(email).getSkillPoints()).isEqualTo(4);
    }

    @Test
    @DisplayName("an award that does not level up grants nothing")
    void grantsNothingWithoutALevelUp() throws Exception {
        String email = "points_none@example.com";
        signInNewPlayer("points_none", email);

        applyExperience(email, 10);

        assertThat(profileOf(email).getSkillPoints()).isZero();
    }

    @Test
    @DisplayName("points accumulate across separate awards")
    void pointsAccumulate() throws Exception {
        String email = "points_acc@example.com";
        String token = signInNewPlayer("points_acc", email);

        applyExperience(email, 100);
        applyExperience(email, 150);
        applyExperience(email, 225);

        assertThat(profileOf(email).getSkillPoints()).isEqualTo(3);
        assertThat(skillTree(token).path("skillPoints").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("earned points can be spent immediately")
    void earnedPointsAreSpendable() throws Exception {
        String email = "points_spend@example.com";
        String token = signInNewPlayer("points_spend", email);

        applyExperience(email, 250);

        unlockSkill(token, skillId("RAPID_EXECUTION"));

        assertThat(profileOf(email).getSkillPoints()).isEqualTo(1);
    }
}