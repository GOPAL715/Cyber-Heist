package com.cyberheist.progression;

import com.cyberheist.player.PlayerProfile;
import org.springframework.stereotype.Service;

/**
 * Applies experience to a player and reports what changed.
 *
 * <p>Deliberately knows nothing about missions or rewards: it takes an XP amount
 * and a profile, and owns the XP/level math only. That keeps it reusable for
 * future sources of XP such as daily challenges or achievements.
 */
@Service
public class ProgressionService {

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

        return new ProgressionResult(
                levelBefore,
                levelAfter,
                profile.getExperience(),
                levelCurve.xpIntoLevel(levelAfter, profile.getExperience()),
                levelCurve.xpForNextLevel(levelAfter),
                levelAfter > levelBefore,
                Math.max(0, levelAfter - levelBefore)
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