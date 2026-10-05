package com.cyberheist.progression;

import org.springframework.stereotype.Component;

/**
 * The level curve.
 *
 * <p><strong>The model.</strong> Advancing from level {@code L} costs
 *
 * <pre>{@code cost(L) = round(baseExperience * growthMultiplier ^ (L - 1))}</pre>
 *
 * with the defaults ({@code baseExperience = 100}, {@code growthMultiplier = 1.5}):
 *
 * <pre>
 *   level 1 ->  100 XP to reach level 2
 *   level 2 ->  150 XP to reach level 3
 *   level 3 ->  225 XP to reach level 4
 *   level 4 ->  338 XP to reach level 5
 * </pre>
 *
 * <p>Those per-level costs sum to the cumulative total needed to <em>hold</em> a
 * level, which is the closed form {@code requiredXp(L) = base * (m^(L-1) - 1) / (m - 1)}:
 *
 * <pre>
 *   level 1 ->    0 total XP
 *   level 2 ->  100 total XP
 *   level 3 ->  250 total XP
 *   level 4 ->  475 total XP
 *   level 5 ->  813 total XP
 * </pre>
 *
 * <p><strong>XP is cumulative and never resets.</strong> A player on 90 XP who
 * earns 50 reaches level 2 holding 140 XP, not 40. That is why the stored
 * {@code experience} column is a running total and the level is derived from it
 * rather than tracked independently; the two cannot drift apart.
 *
 * <p>The curve is pure arithmetic with no persistence, which keeps it trivial to
 * unit test and to retune later. {@code maximumLevel} caps growth well before
 * {@code long} overflow, and every accessor saturates instead of wrapping.
 */
@Component
public class LevelCurve {

    private final ProgressionProperties properties;

    public LevelCurve(ProgressionProperties properties) {
        this.properties = properties;
    }

    /** Total cumulative XP required to hold the given level. Level 1 costs nothing. */
    public long xpRequiredFor(int level) {
        int cap = properties.maximumLevel();

        // Clamp to the cap, but never recurse to compute the cap itself: that
        // would loop forever when maximumLevel is 1.
        int clamped = Math.min(Math.max(level, 1), cap);
        if (clamped == 1) {
            return 0L;
        }

        double m = properties.growthMultiplier();
        // base * (m^(clamped-1) - 1) / (m - 1), the geometric sum of per-level costs.
        double required = properties.baseExperience()
                * (Math.pow(m, clamped - 1L) - 1.0d) / (m - 1.0d);

        if (required >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.round(required);
    }

    /**
     * XP needed to advance from {@code currentLevel} to the next one.
     *
     * <p>Returns {@code 0} at the level cap so callers never divide by it.
     */
    public long xpForNextLevel(int currentLevel) {
        if (currentLevel >= properties.maximumLevel()) {
            return 0L;
        }
        return Math.round(properties.baseExperience()
                * Math.pow(properties.growthMultiplier(), currentLevel - 1L));
    }

    /** XP already accumulated inside the current level band. Never negative. */
    public long xpIntoLevel(int currentLevel, long totalExperience) {
        return Math.max(0L, totalExperience - xpRequiredFor(currentLevel));
    }

    /**
     * The highest level whose cumulative threshold {@code totalExperience} satisfies.
     *
     * <p>Deterministic and monotonic: more XP can never lower the level.
     */
    public int levelFor(long totalExperience) {
        int level = 1;
        int max = properties.maximumLevel();

        while (level < max && totalExperience >= xpRequiredFor(level + 1)) {
            level++;
        }
        return level;
    }

    public int maximumLevel() {
        return properties.maximumLevel();
    }
}