package com.cyberheist.boss.dto;

import com.cyberheist.boss.EncounterStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The state of a boss encounter.
 *
 * <p>Returned on start and after every submission. The puzzle challenge is
 * attached only when there is a live phase to answer, and never carries an
 * answer: the seed re-derives it on the server.
 *
 * @param bossIntegrityPercent integrity as a percentage, derived server-side so
 *                            the bar cannot disagree with the win condition
 * @param rewards             what was actually paid; zero unless VICTORY
 */
public record EncounterState(
        UUID encounterId,
        UUID bossId,
        String bossCode,
        String bossName,
        String bossDifficulty,
        EncounterStatus status,
        int currentStage,
        int stageCount,
        int bossIntegrity,
        int bossIntegrityPercent,
        int reachedStage,
        com.cyberheist.puzzle.dto.PuzzleChallengeView puzzle,
        String stageName,
        String stageDescription,
        long xpAwarded,
        long coinAwarded,
        Instant startedAt,
        Instant expiresAt,
        Instant cooldownUntil,
        String outcomeMessage,
        Rewards rewards,
        Progression progression
) {

    /** What the encounter paid. Present only for a victory. */
    public record Rewards(long experience, long coins) {
    }

    /**
     * The level change the reward caused.
     *
     * <p>{@code skillPointsGained} is reported rather than derived, because it is
     * awarded inside {@code ProgressionService} alongside the level change and a
     * client that recomputed it would be guessing.
     */
    public record Progression(
            int levelBefore,
            int levelAfter,
            int levelsGained,
            int skillPointsGained
    ) {
    }
}