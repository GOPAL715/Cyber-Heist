package com.cyberheist.energy;

import java.time.Instant;

/**
 * The energy figures the client is allowed to see.
 *
 * <p>Everything here is server-computed. The regeneration policy is included so
 * the UI can show "+1 every 5 min" without hardcoding it, and
 * {@code nextRegenerationAt} so the countdown reflects the server's view of when
 * the next unit lands.
 *
 * <p>{@code nextRegenerationAt} is advisory. A client that trusts it to decide
 * whether it can afford a mission will eventually be wrong, which is why the
 * start endpoint re-checks affordability against the server's own figures.
 *
 * @param energy     the refreshed balance
 * @param maximum    the hard cap; the balance never exceeds it
 * @param full       true when the player is at the cap and cannot gain anything
 * @param nextRegenerationAt when the next unit is due, or {@code null} when
 *                           regeneration is disabled
 */
public record EnergySnapshot(
        int energy,
        int maximum,
        boolean regenerationEnabled,
        int regenerationAmount,
        long regenerationIntervalSeconds,
        Instant nextRegenerationAt
) {
    /** True when the player is already at the cap. */
    public boolean isFull() {
        return energy >= maximum;
    }

    /** True when the player can afford a cost right now. */
    public boolean canAfford(int cost) {
        return cost <= 0 || energy >= cost;
    }
}