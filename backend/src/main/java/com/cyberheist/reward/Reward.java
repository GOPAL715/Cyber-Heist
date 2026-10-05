package com.cyberheist.reward;

/**
 * A payout, decided entirely on the server.
 *
 * @param experience XP granted
 * @param coins      coins granted
 */
public record Reward(long experience, long coins) {
    public static Reward none() {
        return new Reward(0L, 0L);
    }
}