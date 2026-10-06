package com.cyberheist.puzzle.dto;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleChallenge;
import com.cyberheist.puzzle.PuzzleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A puzzle as shown to the player.
 *
 * <p>This is the only shape of a puzzle that crosses the API boundary, and it
 * has no field for the answer. Everything a client needs to render and solve the
 * challenge is here; everything needed to judge it stays on the server.
 *
 * <p>{@code sequence} carries the display tokens for every type - the ciphertext
 * for a cipher, the terms for a sequence, the rows for a pattern, the node and
 * edge list for a logic puzzle, the code for a timed one - so the client renders
 * a type without special-casing the payload shape.
 *
 * @param options        selectable options, empty when the player types an answer
 * @param timeLimitSeconds window length, for drawing a countdown only. Whether
 *                        the window has actually closed is decided by the server
 */
public record PuzzleChallengeView(
        UUID puzzleId,
        PuzzleType type,
        MissionDifficulty difficulty,
        String title,
        String question,
        List<String> sequence,
        List<String> options,
        Instant startedAt,
        Instant expiresAt,
        int timeLimitSeconds
) {
    public PuzzleChallengeView {
        sequence = List.copyOf(sequence);
        options = List.copyOf(options);
    }

    /**
     * Projects a server-side challenge for the client.
     *
     * <p>The answer is dropped here and nowhere else, which makes this the single
     * place to audit for answer leakage.
     */
    public static PuzzleChallengeView of(UUID puzzleId,
                                        PuzzleChallenge challenge,
                                        MissionDifficulty difficulty,
                                        Instant startedAt,
                                        Instant expiresAt,
                                        int timeLimitSeconds) {
        return new PuzzleChallengeView(
                puzzleId,
                challenge.type(),
                difficulty,
                challenge.title(),
                challenge.question(),
                challenge.sequence(),
                challenge.options(),
                startedAt,
                expiresAt,
                timeLimitSeconds);
    }

    /** True when the player picks from a list instead of typing. */
    public boolean isMultipleChoice() {
        return !options.isEmpty();
    }
}