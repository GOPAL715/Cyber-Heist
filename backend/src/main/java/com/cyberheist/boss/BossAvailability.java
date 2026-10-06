package com.cyberheist.boss;

/**
 * What the API reports about a boss to the calling player.
 *
 * <p>Derived from the level gate, the cooldown and any encounter the player
 * holds. It is a distinct value from {@code EncounterStatus} on purpose: "you
 * have beaten this before" is not the same fact as "there is a live encounter",
 * and a boss can be on cooldown and still have a finished encounter in history.
 */
public enum BossAvailability {
    /** Can be started right now. */
    AVAILABLE,
    /** Below the required level. */
    LOCKED,
    /** Waiting out a cooldown from a previous attempt. */
    COOLDOWN,
    /** An encounter is already running. */
    ACTIVE
}