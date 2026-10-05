package com.cyberheist.player.dto;

import java.util.UUID;

/**
 * A player's own game state.
 *
 * <p>Returned only for the authenticated principal - there is no variant that
 * exposes another player's profile.
 *
 * <p>{@code xpIntoLevel} and {@code xpForNextLevel} are supplied so the client
 * can render a progress bar without reimplementing the level curve.
 *
 * @param experience   total cumulative XP, which is never reset on level up
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
        int energy
) {
}