package com.cyberheist.player.dto;

import java.util.UUID;

/**
 * A player's own game state.
 *
 * <p>Returned only for the authenticated principal - there is no variant that
 * exposes another player's profile.
 */
public record PlayerProfileResponse(
        UUID id,
        String username,
        String displayName,
        int level,
        long experience,
        long coins,
        int energy
) {
}