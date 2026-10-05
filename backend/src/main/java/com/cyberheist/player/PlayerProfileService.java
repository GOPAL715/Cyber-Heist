package com.cyberheist.player;

import com.cyberheist.config.PlayerProperties;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Creates the game profile that accompanies every new account.
 *
 * <p>Extracted from registration so the initial-values policy lives in one
 * place and can be reused by future phases (character creation, admin tools).
 */
@Service
public class PlayerProfileService {

    private final PlayerProfileRepository repository;
    private final PlayerProperties properties;

    public PlayerProfileService(PlayerProfileRepository repository, PlayerProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Creates a starting profile for the given user.
     *
     * @throws IllegalStateException if the user already has a profile, which
     *                               would mean the invariant was broken
     */
    public PlayerProfile createInitialProfile(UUID userId, String displayName) {
        if (repository.existsByUserId(userId)) {
            throw new IllegalStateException("Player profile already exists for user " + userId);
        }
        PlayerProfile profile = new PlayerProfile(
                UUID.randomUUID(),
                userId,
                displayName,
                properties.initialLevel(),
                properties.initialExperience(),
                properties.initialCoins(),
                properties.initialEnergy()
        );
        return repository.save(profile);
    }
}