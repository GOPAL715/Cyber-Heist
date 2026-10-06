package com.cyberheist.puzzle;

/**
 * The kinds of challenge the puzzle engine can produce.
 *
 * <p>Each constant has exactly one {@link PuzzleProvider} implementation. The
 * enum is stored on {@code puzzle_attempts.puzzle_type} and on
 * {@code missions.puzzle_type}, so a mission decides which challenge the
 * player will face without any client involvement.
 *
 * <p>Adding a type means adding a constant <em>and</em> a provider; nothing in
 * {@code MissionService} changes.
 */
public enum PuzzleType {
    CIPHER,
    SEQUENCE,
    PATTERN,
    LOGIC,
    TIMED;

    /**
     * Short label used in player-facing messages.
     *
     * <p>Kept here rather than in the DTO so that the wording of a puzzle type
     * cannot drift between the API and the client.
     */
    public String label() {
        return switch (this) {
            case CIPHER -> "Cipher";
            case SEQUENCE -> "Sequence";
            case PATTERN -> "Pattern";
            case LOGIC -> "Logic";
            case TIMED -> "Timed";
        };
    }
}