package com.cyberheist.mission;

/**
 * Mission difficulty.
 *
 * <p>Server-defined. A controlled enum rather than free text, so clients can
 * never inject an arbitrary value and rewards stay tied to real tiers.
 */
public enum MissionDifficulty {
    EASY,
    MEDIUM,
    HARD,
    ELITE;

    /** Sort weight, so the UI can order tiers sensibly. */
    public int rank() {
        return ordinal();
    }
}