package com.cyberheist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Server-authoritative energy regeneration.
 *
 * <p>Energy is computed lazily from {@code lastEnergyUpdate} whenever profile
 * state is read or mutated, so no background job sweeps every player. All
 * values come from the server clock; the frontend clock is never trusted.
 *
 * @param maximum      hard cap; energy can never exceed this
 * @param regeneration interval policy
 */
@ConfigurationProperties(prefix = "app.energy")
public record EnergyProperties(
        int maximum,
        Regeneration regeneration
) {
    public EnergyProperties {
        if (maximum <= 0) {
            maximum = 100;
        }
        if (regeneration == null) {
            regeneration = new Regeneration(true, 1, Duration.ofMinutes(5));
        }
    }

    /**
     * @param enabled         master switch, mainly for tests and maintenance
     * @param amount          energy restored per interval
     * @param intervalMinutes length of one regeneration interval, in minutes
     */
    public record Regeneration(
            boolean enabled,
            int amount,
            @DurationUnit(ChronoUnit.MINUTES) Duration intervalMinutes
    ) {
        public Regeneration {
            if (amount <= 0) {
                amount = 1;
            }
            if (intervalMinutes == null || intervalMinutes.isZero() || intervalMinutes.isNegative()) {
                intervalMinutes = Duration.ofMinutes(5);
            }
        }
    }
}