package com.cyberheist.mission.dto;

/**
 * What happened when a player submitted an answer.
 *
 * <p>Every value is decided on the server. A request cannot influence which of
 * these the player gets: the request carries a puzzle id and a string, and
 * nothing else.
 */
public enum PuzzleOutcome {

    /** The answer was correct. The mission is completed and rewards were paid. */
    SOLVED,

    /**
     * The answer was wrong. The mission stays in progress, no rewards are paid,
     * and this puzzle is spent - a new one costs a new mission start.
     */
    INCORRECT,

    /**
     * The window closed before the answer arrived, judged against the server
     * clock. No rewards are paid regardless of whether the answer was right.
     */
    EXPIRED;

    /** True only for the one outcome that pays out. */
    public boolean isRewardable() {
        return this == SOLVED;
    }

    /** Player-facing headline, matching the result screen. */
    public String headline() {
        return switch (this) {
            case SOLVED -> "MISSION COMPLETE";
            case INCORRECT -> "ACCESS DENIED";
            case EXPIRED -> "CONNECTION TIMEOUT";
        };
    }
}