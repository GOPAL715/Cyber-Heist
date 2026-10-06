package com.cyberheist.player;

import com.cyberheist.config.PlayerProperties;
import com.cyberheist.shop.StarterEquipmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates the game profile that accompanies every new account.
 *
 * <p>Extracted from registration so the initial-values policy lives in one
 * place and can be reused by future phases (character creation, admin tools).
 *
 * <p>Also the single place a new player's inventory is seeded. Keeping the
 * starter grant here rather than in {@code RegistrationService} means any future
 * way of creating a player - an admin tool, an import - produces the same
 * starting state instead of an account with no equipment.
 */
@Service
public class PlayerProfileService {

    private final PlayerProfileRepository repository;
    private final PlayerProperties properties;
    private final StarterEquipmentService starterEquipmentService;

    public PlayerProfileService(PlayerProfileRepository repository,
                                PlayerProperties properties,
                                StarterEquipmentService starterEquipmentService) {
        this.repository = repository;
        this.properties = properties;
        this.starterEquipmentService = starterEquipmentService;
    }

    /**
     * Creates a starting profile for the given user, plus the free starter device.
     *
     * @throws IllegalStateException if the user already has a profile, which
     *                               would mean the invariant was broken
     */
    @Transactional
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
        PlayerProfile saved = repository.save(profile);

        // Free and already equipped. Runs in the caller's transaction, so a
        // failure here undoes the account rather than leaving a player with no
        // starting gear.
        starterEquipmentService.grantStarterItem(userId);

        return saved;
    }
}