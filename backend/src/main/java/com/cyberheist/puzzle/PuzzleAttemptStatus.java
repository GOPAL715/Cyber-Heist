package com.cyberheist.puzzle;

/**
 * Lifecycle of one generated puzzle.
 *
 * <p>{@link #ACTIVE} is the only state in which a submission is accepted. The
 * row is moved out of it exactly once, which is what makes double submission
 * and double rewards structurally impossible rather than merely unlikely.
 */
public enum PuzzleAttemptStatus {
    /** Generated and awaiting an answer; the only submittable state. */
    ACTIVE,
    /** Submitted correctly, or already solved by an earlier submission. */
    SUCCEEDED,
    /** Submitted incorrectly. The puzzle is spent; a new one requires a new start. */
    FAILED,
    /** The window closed before any submission arrived. */
    EXPIRED;

    /** True only for a puzzle that may still accept a submission. */
    public boolean isSubmittable() {
        return this == ACTIVE;
    }
}