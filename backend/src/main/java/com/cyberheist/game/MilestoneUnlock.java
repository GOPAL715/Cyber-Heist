package com.cyberheist.game;

/**
 * A milestone that was unlocked by the request that just completed.
 *
 * <p>Returned by every Phase 7 entry point that can unlock something, which is how
 * the notification reaches the player without the client polling for it: the server
 * already knows what it just awarded, so it says so in the response it was building
 * anyway. No extra request, and nothing to guess at on the client.
 *
 * <p>The reward figures are the final, post-bonus amounts that were actually paid -
 * not the catalogue base values - so a notification cannot advertise more than the
 * profile received.
 */
public record MilestoneUnlock(
        MilestoneKind kind,
        String code,
        String name,
        String description,
        String icon,
        long experience,
        long coins,
        int levelGained
) {
    public MilestoneUnlock {
        // levelGained is 0 when the reward did not cross a level boundary.
        levelGained = Math.max(0, levelGained);
    }
}
