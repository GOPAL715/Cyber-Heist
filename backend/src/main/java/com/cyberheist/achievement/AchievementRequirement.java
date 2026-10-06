package com.cyberheist.achievement;

/**
 * The counters an achievement can be measured against.
 *
 * <p>This is a closed set, and that is the point of it. An achievement row names
 * one of these constants and an integer target; there is no expression, no
 * formula and no field that a database string could be interpreted as code. Adding
 * a new kind of milestone means adding a constant here, a case in
 * {@code PlayerStatsService} that can actually produce the number, and a row in the
 * catalogue - never a value the database evaluates.
 *
 * <p>Several of these are satisfied by more than one table. {@code BOSSES_DEFEATED},
 * for instance, is read from boss encounters, and {@code LOGIN_STREAK} from the
 * player's own streak row rather than from any counter.
 */
public enum AchievementRequirement {
    MISSIONS_COMPLETED,
    PUZZLES_SOLVED,
    CIPHER_SOLVED,
    LOGIC_SOLVED,
    PATTERN_SOLVED,
    SEQUENCE_SOLVED,
    TIMED_SOLVED,
    PLAYER_LEVEL,
    COINS_EARNED,
    ITEMS_OWNED,
    ITEMS_EQUIPPED,
    SKILLS_UNLOCKED,
    SKILL_LEVEL,
    BOSSES_DEFEATED,
    DAILY_CHALLENGES_COMPLETED,
    LOGIN_STREAK
}
