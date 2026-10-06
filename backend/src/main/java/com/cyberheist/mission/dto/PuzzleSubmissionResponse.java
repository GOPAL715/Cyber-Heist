package com.cyberheist.mission.dto;

import com.cyberheist.energy.EnergySnapshot;
import com.cyberheist.puzzle.PuzzleType;
import com.cyberheist.progression.ProgressionResult;

import java.util.UUID;

/**
 * The result of submitting a puzzle answer.
 *
 * <p>The client sends a puzzle id and a string. Everything it learns back is
 * computed here, including whether the answer was right. There is no field a
 * client could have set and no answer is echoed back - a wrong submission tells
 * the player it was wrong, not what it should have been, so the challenge keeps
 * its value on a retry.
 *
 * @param rewards           always zero unless {@code outcome} is {@code SOLVED}
 * @param missionCompleted  true when this submission finished the mission
 * @param alreadySolved     true when the puzzle had already been answered
 *                          correctly, so this call changed nothing and paid
 *                          nothing. That is what a duplicate submit returns.
 * @param canRetry          true when starting the mission again produces a new
 *                          puzzle; false once the mission is complete
 */
public record PuzzleSubmissionResponse(
        MissionCompletionResponse.MissionSummary mission,
        PuzzleType puzzleType,
        PuzzleOutcome outcome,
        String message,
        MissionCompletionResponse.Rewards rewards,
        ProgressionResult progression,
        EnergySnapshot player,
        boolean missionCompleted,
        boolean alreadySolved,
        boolean canRetry
) {
    /** A solved submission, which is the only one that pays. */
    public static PuzzleSubmissionResponse solved(MissionCompletionResponse.MissionSummary mission,
                                                  PuzzleType puzzleType,
                                                  String message,
                                                  MissionCompletionResponse.Rewards rewards,
                                                  ProgressionResult progression,
                                                  EnergySnapshot player) {
        return new PuzzleSubmissionResponse(mission, puzzleType, PuzzleOutcome.SOLVED, message,
                rewards, progression, player, true, false, false);
    }

    /** A failed or expired submission. Never carries a reward. */
    public static PuzzleSubmissionResponse rejected(MissionCompletionResponse.MissionSummary mission,
                                                    PuzzleType puzzleType,
                                                    PuzzleOutcome outcome,
                                                    String message,
                                                    ProgressionResult progression,
                                                    EnergySnapshot player) {
        boolean expired = outcome == PuzzleOutcome.EXPIRED;
        return new PuzzleSubmissionResponse(mission, puzzleType, outcome, message,
                new MissionCompletionResponse.Rewards(0L, 0L), progression, player,
                false, false, !expired);
    }

    /** A repeat submission against an already-solved puzzle. Awards nothing. */
    public static PuzzleSubmissionResponse replay(MissionCompletionResponse.MissionSummary mission,
                                                  PuzzleType puzzleType,
                                                  String message,
                                                  ProgressionResult progression,
                                                  EnergySnapshot player) {
        return new PuzzleSubmissionResponse(mission, puzzleType, PuzzleOutcome.SOLVED, message,
                new MissionCompletionResponse.Rewards(0L, 0L), progression, player,
                true, true, false);
    }
}