package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.dto.PuzzleChallengeView;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What {@link PuzzleService} produced for a mission: the seed it generated from,
 * the challenge (answer included) and the window it must be answered in.
 *
 * <p>Server-internal. Only the {@link PuzzleChallengeView} projection of the
 * challenge is allowed to leave the process, and that projection has no answer
 * field. The seed travels beside the challenge for exactly one reason - the
 * attempt row must persist it so validation can re-derive the answer later - and
 * it is never part of any response.
 *
 * @param seed      the generation seed; persisted on the attempt, never transmitted
 * @param challenge the challenge including the expected answer
 * @param window    how long the player has; the caller stamps the expiry from it
 */
public record GeneratedPuzzle(long seed, PuzzleChallenge challenge, Duration window) {

    public GeneratedPuzzle {
        if (challenge == null) {
            throw new IllegalArgumentException("A generated puzzle must carry a challenge");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Puzzle window must be positive");
        }
    }

    /** Drops the answer and labels the challenge for the client. */
    public PuzzleChallengeView viewOf(UUID puzzleId,
                                      MissionDifficulty difficulty,
                                      Instant startedAt,
                                      Instant expiresAt) {
        return PuzzleChallengeView.of(
                puzzleId, challenge, difficulty, startedAt, expiresAt, (int) window.toSeconds());
    }
}