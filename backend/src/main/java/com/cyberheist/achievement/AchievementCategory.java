package com.cyberheist.achievement;

/**
 * The groups an achievement can belong to, which is also how the gallery is
 * laid out.
 *
 * <p>Kept to eight on purpose. A larger set would make the achievement page a
 * wall of near-identical entries and would give the catalogue somewhere to put
 * milestones that do not represent real progress.
 */
public enum AchievementCategory {
    MISSIONS,
    PUZZLES,
    PROGRESSION,
    ECONOMY,
    EQUIPMENT,
    SKILLS,
    BOSSES,
    DAILY
}
