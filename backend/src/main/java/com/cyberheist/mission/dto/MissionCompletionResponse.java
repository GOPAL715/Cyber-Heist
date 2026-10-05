package com.cyberheist.mission.dto;

import com.cyberheist.progression.ProgressionResult;

import java.util.UUID;

/**
 * Result of a successful mission completion.
 *
 * <p>Every value here is computed server-side. The request body is empty, so a
 * client cannot influence any of it.
 *
 * @param alreadyCompleted true when this call did not award anything because the
 *                         mission had already been completed
 */
public record MissionCompletionResponse(
        MissionSummary mission,
        Rewards rewards,
        ProgressionResult progression,
        PlayerState player,
        boolean alreadyCompleted
) {
    /** Minimal mission identity echoed back for client-side confirmation. */
    public record MissionSummary(UUID id, String code, String title) {
    }

    /** What the mission paid out. */
    public record Rewards(long experience, long coins) {
    }

    /** The player's authoritative post-completion state. */
    public record PlayerState(int level, long experience, long coins, int energy) {
    }
}