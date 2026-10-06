package com.cyberheist.daily;

/**
 * The per-day totals a daily challenge can be measured against.
 *
 * <p>A smaller set than {@code AchievementRequirement}, because a daily objective has
 * to be completable in a sitting. Each one is incremented inside the transaction
 * that performed the event, so a total can only move because something real
 * happened.
 */
public enum DailyMetric {
    MISSIONS_COMPLETED,
    PUZZLES_SOLVED,
    XP_EARNED,
    COINS_EARNED,
    BOSSES_DEFEATED
}
