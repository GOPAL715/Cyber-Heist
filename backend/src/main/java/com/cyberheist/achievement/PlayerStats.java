package com.cyberheist.achievement;

import java.util.EnumMap;
import java.util.Map;

/**
 * A snapshot of everything the milestone catalogue can be measured against.
 *
 * <p>Built once per evaluation and then consulted in memory, which is what keeps a
 * thirty-row catalogue from becoming thirty separate round trips. Only the
 * requirements actually referenced by an active achievement are measured at all, so
 * a catalogue using three requirement types costs three queries rather than sixteen.
 *
 * <p>Every value here is read from a table that records something that actually
 * happened. Nothing is accumulated in memory between requests, which is what makes
 * a retried submission, a double-clicked button or a concurrent duplicate
 * impossible to count twice.
 */
public final class PlayerStats {

    private final Map<AchievementRequirement, Long> measured;

    private PlayerStats(Map<AchievementRequirement, Long> measured) {
        this.measured = measured;
    }

    /** An empty snapshot, used when no requirement needs measuring. */
    public static PlayerStats empty() {
        return new PlayerStats(new EnumMap<>(AchievementRequirement.class));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The measured value for a requirement, or zero when it was not measured.
     *
     * <p>Returning zero rather than throwing means an achievement whose requirement
     * happens not to be measured simply stays locked, which is the safe direction to
     * fail in: a milestone never unlocks on the strength of a number nobody looked up.
     */
    public long of(AchievementRequirement requirement) {
        return measured.getOrDefault(requirement, 0L);
    }

    /** Collects measured values. */
    public static final class Builder {

        private final Map<AchievementRequirement, Long> values =
                new EnumMap<>(AchievementRequirement.class);

        private Builder() {
        }

        public Builder measure(AchievementRequirement requirement, long value) {
            values.put(requirement, Math.max(0L, value));
            return this;
        }

        public PlayerStats build() {
            return new PlayerStats(values);
        }
    }
}
