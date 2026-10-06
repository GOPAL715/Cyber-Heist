package com.cyberheist.skill.dto;

import com.cyberheist.bonus.PlayerBonusService;
import com.cyberheist.skill.SkillBranch;
import com.cyberheist.skill.SkillLevel;
import java.util.List;
import java.util.UUID;

/**
 * The whole skill tree as one player sees it.
 *
 * <p>Everything here is server-derived. The client renders this and cannot
 * influence any of it: the balance, the levels, the costs, the prerequisites,
 * whether a skill is available and why are all decided by
 * {@code SkillTreeService}.
 *
 * @param skillPoints       unspent points
 * @param branches          one entry per branch, always all four, in enum order
 * @param bonuses           what equipment and skills each contributed, before
 *                          the shared cap - for attribution only
 * @param effectiveBonuses  the capped, combined totals the game actually
 *                          applies. This is what the screen must show as the
 *                          player's bonus; showing the uncapped split instead
 *                          would claim a bonus the engine is not using
 */
public record SkillTreeResponse(
        int skillPoints,
        List<BranchView> branches,
        PlayerBonusService.Breakdown bonuses,
        List<BonusView> effectiveBonuses
) {

    /**
     * A capped bonus, as a percentage.
     *
     * @param type    one of the five controlled effect types
     * @param percent the capped figure the reward and energy paths apply
     */
    public record BonusView(String type, int percent) {
    }

    /**
     * One branch and the skills in it.
     *
     * <p>Present even when empty, so the screen renders a stable set of columns
     * rather than inventing placeholders for branches that happen to have no
     * content.
     */
    public record BranchView(SkillBranch branch, List<SkillView> skills) {
    }

    /**
     * One skill, and this player's relationship with it.
     *
     * @param currentLevel  level reached; 0 when never taken
     * @param maxLevel      the cap from the skill definition
     * @param levels        every level with its cost and effect, so the tree can
     *                      show what the player is working toward
     * @param nextCost      points the next level costs; null when maxed
     * @param canUnlock     the server's verdict, used directly for the button
     * @param locked        true when a prerequisite is unmet
     * @param blockedReason player-safe explanation when it cannot be taken
     */
    public record SkillView(
            UUID id,
            String code,
            String name,
            String description,
            SkillBranch branch,
            int currentLevel,
            int maxLevel,
            List<LevelView> levels,
            Integer nextCost,
            String nextEffectType,
            Integer nextEffectValue,
            boolean canUnlock,
            boolean locked,
            String blockedReason,
            List<PrerequisiteView> prerequisites
    ) {
    }

    /**
     * What one level costs and grants.
     *
     * @param effectType   one of the five controlled effect types
     * @param effectValue  percentage granted at this level, not an increment
     */
    public record LevelView(
            int level,
            int cost,
            String effectType,
            int effectValue
    ) {
        public static LevelView of(SkillLevel level) {
            return new LevelView(
                    level.getLevel(),
                    level.getSkillPointCost(),
                    level.getEffectType().name(),
                    level.getEffectValue());
        }
    }

    /**
     * A prerequisite and the player's progress against it.
     *
     * @param requiredLevel the level the gated skill needs
     * @param currentLevel  what the player has, so the client can show progress
     *                      without doing the comparison itself
     */
    public record PrerequisiteView(
            UUID skillId,
            String code,
            String name,
            int requiredLevel,
            int currentLevel
    ) {
    }

    /**
     * Why a skill is unavailable.
     *
     * @param message   player-safe explanation
     * @param skillId   the prerequisite that was not met
     * @param requiredLevel the level it had to reach
     */
    public record LockedReason(String message, UUID skillId, int requiredLevel) {
    }
}