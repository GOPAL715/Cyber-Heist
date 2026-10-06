package com.cyberheist.daily;

import com.cyberheist.reward.Reward;

/**
 * One objective as the client sees it: what it asks for, what the player has done,
 * and what it pays.
 *
 * <p>Progress is the server's measured total clamped to the requirement, and the
 * reward is the post-bonus figure that would actually be paid. The client renders
 * these; it does not compute them.
 *
 * @param percentComplete 0-100 for the progress bar
 */
public record DailyChallengeView(
        String code,
        String title,
        String description,
        DailyMetric requirementType,
        long progress,
        int requirement,
        int percentComplete,
        boolean completed,
        java.time.Instant completedAt,
        Reward reward
) {

    public static DailyChallengeView of(DailyChallenge challenge, DailyChallengeProgress row,
                                        long measured) {
        int requirement = challenge.getRequirementValue();
        int progress = row == null
                ? clamp(measured, requirement)
                : Math.max(row.reportedProgress(challenge), clamp(measured, requirement));
        boolean completed = row != null && row.isCompleted();

        return new DailyChallengeView(
                challenge.getCode(),
                challenge.getTitle(),
                challenge.getDescription(),
                challenge.getRequirement(),
                progress,
                requirement,
                completed ? 100 : percent(progress, requirement),
                completed,
                row == null ? null : row.getCompletedAt(),
                challenge.reward());
    }

    private static int percent(int progress, int requirement) {
        if (requirement <= 0) {
            return 100;
        }
        return (int) Math.min(100L, (long) progress * 100L / requirement);
    }

    private static int clamp(long measured, int requirement) {
        if (measured <= 0) {
            return 0;
        }
        return (int) Math.min(measured, requirement);
    }
}
