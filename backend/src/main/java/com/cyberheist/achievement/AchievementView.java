package com.cyberheist.achievement;

import com.cyberheist.reward.Reward;

/**
 * One achievement as the client sees it: the definition, plus this player's state
 * against it.
 *
 * <p>Everything here is server-computed. The progress figure is the measured
 * counter clamped to the requirement, and {@code percentComplete} is derived from
 * those two numbers, so the client has nothing to calculate and therefore nothing to
 * get wrong or to disagree with the server about.
 *
 * @param percentComplete 0-100, for the progress bar
 */
public record AchievementView(
        String code,
        String name,
        String description,
        AchievementCategory category,
        AchievementRequirement requirementType,
        long progress,
        int requirement,
        int percentComplete,
        boolean unlocked,
        java.time.Instant unlockedAt,
        String icon,
        Reward reward
) {

    /**
     * Combines a definition with the player's row, if they have one.
     *
     * <p>A missing row is a normal state, not an error: it means the player has not
     * been evaluated against this milestone yet, or has not reached it. It is
     * reported as zero progress and locked rather than being hidden, because an
     * unearned milestone is exactly what the gallery exists to show.
     *
     * @param measured the freshly measured counter value for this requirement
     */
    public static AchievementView of(Achievement achievement, PlayerAchievement row, long measured) {
        int requirement = achievement.getRequirementValue();
        int progress = row == null
                ? clamp(measured, requirement)
                : Math.max(row.reportedProgress(achievement), clamp(measured, requirement));
        boolean unlocked = row != null && row.isUnlocked();

        return new AchievementView(
                achievement.getCode(),
                achievement.getName(),
                achievement.getDescription(),
                achievement.getCategory(),
                achievement.getRequirement(),
                progress,
                requirement,
                percent(progress, requirement),
                unlocked,
                row == null ? null : row.getUnlockedAt(),
                achievement.getIcon(),
                achievement.reward());
    }

    /**
     * Percentage complete, rounded down.
     *
     * <p>An unlocked milestone always reads 100 even if its stored progress is stale,
     * so a bar cannot show a completed entry as partly undone.
     */
    private static int percent(int progress, int requirement) {
        if (requirement <= 0) {
            return 100;
        }
        long raw = (long) progress * 100L / requirement;
        return (int) Math.min(100L, raw);
    }

    private static int clamp(long measured, int requirement) {
        if (measured <= 0) {
            return 0;
        }
        return (int) Math.min(measured, requirement);
    }
}
