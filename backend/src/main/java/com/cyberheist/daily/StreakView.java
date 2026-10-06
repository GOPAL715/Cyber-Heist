package com.cyberheist.daily;

import com.cyberheist.reward.Reward;
import java.util.List;

/**
 * A player's streak, and what is attached to reaching each threshold.
 *
 * <p>{@code nextMilestoneDays} is informational: it lets the daily page say which
 * reward is the next one in sight without the client hard-coding the ladder. It is
 * {@code null} once every milestone has been reached.
 *
 * @param claimed  whether this account has already been paid for that milestone
 * @param reached  whether the current streak is at or past it
 */
public record StreakView(
        int current,
        int longest,
        List<StreakMilestoneView> milestones,
        Integer nextMilestoneDays
) {

    public record StreakMilestoneView(int days, Reward reward, boolean claimed, boolean reached) {
    }
}
