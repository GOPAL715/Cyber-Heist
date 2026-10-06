package com.cyberheist.player.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A player's own game state.
 *
 * <p>Returned only for the authenticated principal - there is no variant that
 * exposes another player's profile.
 *
 * <p>{@code xpIntoLevel} and {@code xpForNextLevel} are supplied so the client
 * can render a progress bar without reimplementing the level curve. The energy
 * fields do the same job for regeneration: the client can draw
 * "82 / 100, +1 every 5 min" without hardcoding either number, which is why the
 * profile endpoint grew them rather than the game growing a second round trip
 * for a dedicated energy endpoint.
 *
 * @param experience          total cumulative XP, never reset on level up
 * @param energy              the refreshed balance, computed by the server
 * @param energyMaximum       the cap the balance can never exceed
 * @param energyRegenerationEnabled whether passive regeneration is switched on
 * @param energyRegenerationAmount  units restored per interval
 * @param energyRegenerationIntervalSeconds length of one interval
 * @param nextEnergyAt        when the next unit is due. Advisory only: the server
 *                             re-checks affordability when a mission starts, so
 *                             a client that trusts this cannot overdraw itself.
 */
public record PlayerProfileResponse(
        UUID id,
        String username,
        String displayName,
        int level,
        long experience,
        long xpIntoLevel,
        long xpForNextLevel,
        long coins,
        int energy,
        int energyMaximum,
        boolean energyRegenerationEnabled,
        int energyRegenerationAmount,
        long energyRegenerationIntervalSeconds,
        Instant nextEnergyAt
) {
}
