package com.cyberheist.progression;

import com.cyberheist.player.PlayerProfile;
import org.springframework.stereotype.Service;

/**
 * Applies experience to a player and reports what changed.
 *
 * <p>Deliberately knows nothing about missions or rewards: it takes an XP amount
 * and a profile, and owns the XP/level math only. That keeps it reusable for
 * future sources of XP such as daily challenges or achievements.
 *
 * <h2>Skill points</h2>
 * Phase 5 makes this the single place a level changes, and therefore the single
 * place a skill point is granted. Hooking it here rather than in
 * {@code RewardService} means every future XP source gets the grant for free and
 * two sources cannot disagree about how many were earned.
 *
 * <p>The amount is {@code levelsGained} rather than a flat 1, so one large reward
 * that crosses several thresholds grants a point for each level and not one for
 * the whole jump.
 */
@Service
public class ProgressionService {

    /** Skill points granted per level gained. */
    private static final int SKILL_POINTS_PER_LEVEL = 1;

    private final LevelCurve levelCurve;

    public ProgressionService(LevelCurve levelCurve) {
        this.levelCurve = levelCurve;
    }

    /**
     * Awards XP to the profile, recomputing the level and handling multi-level jumps.
     *
     * <p>Callers are responsible for the surrounding transaction; this method
     * performs no persistence of its own.
     *
     * @return what changed, for the completion response
     */
    public ProgressionResult awardExperience(PlayerProfile profile, long amount) {
        int levelBefore = profile.addExperience(amount, levelCurve);
        int levelAfter = profile.getLevel();

        int levelsGained = Math.max(0, levelAfter - levelBefore);
        // One point per level crossed, so a jump from level 1 to level 3 pays 2.
        profile.addSkillPoints(levelsGained * SKILL_POINTS_PER_LEVEL);

        return new ProgressionResult(
                levelBefore,
                levelAfter,
                profile.getExperience(),
                levelCurve.xpIntoLevel(levelAfter, profile.getExperience()),
                levelCurve.xpForNextLevel(levelAfter),
                levelsGained > 0,
                levelsGained
        );
    }

    /** Current level band details, used by the player profile endpoint. */
    public ProgressionResult describe(PlayerProfile profile) {
        int level = profile.getLevel();
        return new ProgressionResult(
                level,
                level,
                profile.getExperience(),
                levelCurve.xpIntoLevel(level, profile.getExperience()),
                levelCurve.xpForNextLevel(level),
                false,
                0
        );
    }

    public LevelCurve levelCurve() {
        return levelCurve;
    }
}