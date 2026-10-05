package com.cyberheist.progression;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable progression parameters.
 *
 * @param baseExperience    XP required for the very first level up (level 1 -> 2)
 * @param growthMultiplier  each level costs this factor more than the previous one
 * @param maximumLevel      hard ceiling, which also keeps the curve far away from overflow
 */
@ConfigurationProperties(prefix = "app.progression")
public record ProgressionProperties(
        long baseExperience,
        double growthMultiplier,
        int maximumLevel
) {
    public ProgressionProperties {
        if (baseExperience <= 0) {
            baseExperience = 100;
        }
        if (growthMultiplier <= 1.0d) {
            growthMultiplier = 1.5d;
        }
        if (maximumLevel < 1) {
            maximumLevel = 100;
        }
    }
}