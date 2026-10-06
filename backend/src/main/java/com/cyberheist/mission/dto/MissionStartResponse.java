package com.cyberheist.mission.dto;

import com.cyberheist.energy.EnergySnapshot;
import com.cyberheist.mission.Mission;
import com.cyberheist.mission.MissionCategory;
import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.mission.MissionStatus;
import com.cyberheist.puzzle.PuzzleType;
import com.cyberheist.puzzle.dto.PuzzleChallengeView;

import java.util.UUID;

/**
 * The response to starting a mission: the mission's new state <em>and</em> the
 * puzzle the player now has to solve.
 *
 * <p><strong>Deliberately flattened rather than nested.</strong> Phase 3 has to
 * add a puzzle to the Phase 2 start response, and nesting it under a
 * {@code mission} key would silently move every existing field, breaking any
 * deployed client for no benefit. Repeating the mission fields here means the
 * Phase 2 body is a strict subset of this one: {@code data.code},
 * {@code data.status} and {@code data.startable} still resolve exactly as
 * before, and {@code data.puzzle} is new.
 *
 * <p>{@code puzzle} carries no answer. See {@link PuzzleChallengeView}.
 *
 * @param player authoritative energy figures after the mission's cost was charged
 */
public record MissionStartResponse(
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
        String blockedReason,
        PuzzleChallengeView puzzle,
        int attemptCount,
        EnergySnapshot player
) {
    /** Flattens a mission response and attaches the generated challenge. */
    public static MissionStartResponse of(MissionResponse mission,
                                          PuzzleChallengeView puzzle,
                                          int attemptCount,
                                          EnergySnapshot player) {
        return new MissionStartResponse(
                mission.id(),
                mission.code(),
                mission.title(),
                mission.description(),
                mission.category(),
                mission.difficulty(),
                mission.puzzleType(),
                mission.requiredLevel(),
                mission.xpReward(),
                mission.coinReward(),
                mission.energyCost(),
                mission.estimatedDurationSeconds(),
                mission.status(),
                mission.locked(),
                mission.lockReason(),
                mission.startable(),
                mission.blockedReason(),
                puzzle,
                attemptCount,
                player);
    }
}