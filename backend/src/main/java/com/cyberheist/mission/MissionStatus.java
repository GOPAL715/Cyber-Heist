package com.cyberheist.mission;

/**
 * A mission's state for one specific player.
 *
 * <p>This is a derived value, not a stored column on the mission: a mission the
 * player has never touched is {@link #NOT_STARTED}.
 */
public enum MissionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED;

    public boolean isStarted() {
        return this != NOT_STARTED;
    }

    public boolean isCompleted() {
        return this == COMPLETED;
    }
}