package com.cyberheist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Starting values for a freshly registered player.
 *
 * <p>Keeping them configurable means balance changes do not require a code
 * change or a migration.
 */
@ConfigurationProperties(prefix = "app.player")
public record PlayerProperties(
        int initialLevel,
        long initialExperience,
        long initialCoins,
        int initialEnergy,
        int experiencePerLevel
) {
}