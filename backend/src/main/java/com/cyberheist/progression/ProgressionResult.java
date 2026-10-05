package com.cyberheist.progression;

/**
 * The outcome of applying an experience reward to a player.
 *
 * @param levelBefore    level before the reward was applied
 * @param levelAfter     level after the reward was applied
 * @param experience     total cumulative XP after the reward
 * @param xpIntoLevel    XP accumulated inside the new level band
 * @param xpForNextLevel XP still required to reach the following level
 * @param leveledUp      true when the reward crossed at least one threshold
 * @param levelsGained   how many levels were gained (0 when none)
 */
public record ProgressionResult(
        int levelBefore,
        int levelAfter,
        long experience,
        long xpIntoLevel,
        long xpForNextLevel,
        boolean leveledUp,
        int levelsGained
) {
}