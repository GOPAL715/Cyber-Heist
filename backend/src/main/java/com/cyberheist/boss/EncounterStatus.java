package com.cyberheist.boss;

/**
 * Where a boss encounter has got to.
 *
 * <p>{@code ACTIVE} is the only state in which a puzzle may be submitted, and it
 * is the only state in which a player holds an encounter. The other three are
 * terminal, which is what makes reward duplication structurally impossible: a
 * replayed final submission finds the encounter already {@code VICTORY} and is
 * refused before any reward is considered.
 */
public enum EncounterStatus {

    /** Running. Exactly one per player. */
    ACTIVE(true),
    /** All stages cleared. The only state that pays. */
    VICTORY(false),
    /** A wrong answer, or a stage window that closed. */
    DEFEATED(false),
    /** The encounter ran past its window without being resolved. */
    EXPIRED(false);

    private final boolean open;

    EncounterStatus(boolean open) {
        this.open = open;
    }

    /** True while the encounter can still be progressed. */
    public boolean isOpen() {
        return open;
    }

    /** True once the encounter can no longer change. */
    public boolean isTerminal() {
        return !open;
    }
}