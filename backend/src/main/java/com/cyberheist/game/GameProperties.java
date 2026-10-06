package com.cyberheist.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Game-wide settings that are neither balance nor infrastructure.
 *
 * <p>Phase 7 needs exactly two things the rest of the application does not have
 * a word for: which timezone decides what "today" means, and the key that makes
 * today's daily challenges deterministic. Both are configuration rather than
 * constants so that a deployment can change the business day without a code
 * change, and so the daily rotation can be re-cut deliberately by rotating the
 * key rather than by editing a seed.
 *
 * @param timezone             the business timezone every daily calculation uses
 * @param dailySeedKey         mixed into the daily seed; rotating it re-cuts the rotation
 * @param challengesPerDay     how many objectives each day draws from the pool
 * @param recentAchievementLimit  how many recent unlocks {@code /achievements/recent} returns
 */
@ConfigurationProperties(prefix = "app.game")
public record GameProperties(
        String timezone,
        String dailySeedKey,
        int challengesPerDay,
        int recentAchievementLimit
) {
    /**
     * The zone used when the configured one cannot be resolved.
     *
     * <p>A wrong timezone name would otherwise stop the application from starting
     * at all, which is a worse failure than falling back to UTC and being visible
     * about it in configuration.
     */
    public static final String FALLBACK_TIMEZONE = "UTC";

    public GameProperties {
        if (timezone == null || timezone.isBlank()) {
            timezone = FALLBACK_TIMEZONE;
        }
        if (dailySeedKey == null || dailySeedKey.isBlank()) {
            dailySeedKey = "cyber-heist";
        }
        // Three is the designed set size. Anything below two would make a day
        // trivially completable and anything above four turns the daily page into
        // a checklist.
        if (challengesPerDay < 2) {
            challengesPerDay = 3;
        }
        if (challengesPerDay > 8) {
            challengesPerDay = 8;
        }
        if (recentAchievementLimit < 1) {
            recentAchievementLimit = 10;
        }
        if (recentAchievementLimit > 50) {
            recentAchievementLimit = 50;
        }
    }
}
