package com.cyberheist.puzzle;

import java.util.List;

/**
 * A generated challenge, including the answer.
 *
 * <p><strong>This type never crosses the API boundary.</strong> It exists only
 * inside the server, where {@link PuzzleService} derives the answer from a seed
 * to validate a submission. The client-facing projection is
 * {@link com.cyberheist.puzzle.dto.PuzzleChallengeView}, which is built by
 * dropping {@link #expectedAnswer()}.
 *
 * <p>Because the answer is re-derived rather than stored, there is no column to
 * leak and no value to steal from a database dump.
 *
 * @param sequence      display tokens shown to the player (cipher text, the
 *                      terms of a sequence, the nodes of a graph). Never empty.
 * @param options       multiple-choice options, or an empty list when the
 *                      player types a free-text answer. Always distinct.
 * @param expectedAnswer the single accepted answer, in normalised form.
 */
public record PuzzleChallenge(
        PuzzleType type,
        String title,
        String question,
        List<String> sequence,
        List<String> options,
        String expectedAnswer
) {
    public PuzzleChallenge {
        sequence = List.copyOf(sequence);
        options = List.copyOf(options);
    }

    /** True when the player picks from a list rather than typing. */
    public boolean isMultipleChoice() {
        return !options.isEmpty();
    }
}