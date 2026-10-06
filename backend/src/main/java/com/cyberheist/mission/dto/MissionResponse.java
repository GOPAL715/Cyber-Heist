package com.cyberheist.mission.dto;

import com.cyberheist.mission.Mission;
import com.cyberheist.mission.MissionCategory;
import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.mission.MissionStatus;
import com.cyberheist.puzzle.PuzzleType;

import java.util.UUID;

/**
 * A mission as shown to the player, including their own status and whether the
 * level requirement is met.
 *
 * @param locked       true when the player's level is below {@code requiredLevel}
 * @param lockReason   player-safe explanation, {@code null} when not locked
 * @param startable    true when the player may start it right now
 * @param blockedReason why it cannot be started, {@code null} when it can
 * @param puzzleType   the puzzle family this mission generates, so the board can
 *                     label a mission without a second request
 */
public record MissionResponse(
        UUID id,
        String code,
        String title,
        String description,
        MissionCategory category,
        MissionDifficulty difficulty,
        PuzzleType puzzleType,
        int requiredLevel,
        int xpReward,
        long coinReward,
        int energyCost,
        int estimatedDurationSeconds,
        MissionStatus status,
        boolean locked,
        String lockReason,
        boolean startable,
        String blockedReason
) {
    /** Builds the response from a mission and the caller's current state. */
    public static MissionResponse of(Mission mission, int playerLevel, MissionStatus status) {
        boolean locked = !mission.isUnlockedFor(playerLevel);
        boolean completed = status.isCompleted();

        String lockReason = locked
                ? "Requires level " + mission.getRequiredLevel()
                : null;

        String blockedReason = null;
        if (!mission.isActive()) {
            blockedReason = "Mission is currently unavailable";
        } else if (completed) {
            blockedReason = "Already completed";
        }

        // A locked or already-finished mission cannot be started; energy is
        // deliberately not surfaced here so the UI does not pre-judge affordability.
        boolean startable = !locked && !completed && mission.isActive();

        return new MissionResponse(
                mission.getId(),
                mission.getCode(),
                mission.getTitle(),
                mission.getDescription(),
                mission.getCategory(),
                mission.getDifficulty(),
                mission.getPuzzleType(),
                mission.getRequiredLevel(),
                mission.getXpReward(),
                mission.getCoinReward(),
                mission.getEnergyCost(),
                mission.getEstimatedDurationSeconds(),
                status,
                locked,
                lockReason,
                startable,
                blockedReason
        );
    }
}