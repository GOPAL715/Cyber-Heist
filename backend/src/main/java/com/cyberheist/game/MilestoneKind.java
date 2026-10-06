package com.cyberheist.game;

/**
 * What sort of milestone produced an unlock.
 *
 * <p>Carried on the unlock record so one notification component can render all three
 * cases without the client having to infer which system it came from.
 */
public enum MilestoneKind {
    ACHIEVEMENT,
    DAILY_CHALLENGE,
    STREAK
}
