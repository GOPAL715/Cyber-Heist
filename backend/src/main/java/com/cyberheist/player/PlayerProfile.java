package com.cyberheist.player;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Per-user game state.
 *
 * <p>Created automatically when a user registers so that later phases (missions,
 * XP, upgrades, skills) have somewhere to write without a schema redesign.
 */
@Entity
@Table(name = "player_profiles")
public class PlayerProfile extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 32)
    private String displayName;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "experience", nullable = false)
    private long experience;

    @Column(name = "coins", nullable = false)
    private long coins;

    @Column(name = "energy", nullable = false)
    private int energy;

    protected PlayerProfile() {
        // for JPA
    }

    public PlayerProfile(UUID id, UUID userId, String displayName, int level, long experience, long coins, int energy) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
        this.level = level;
        this.experience = experience;
        this.coins = coins;
        this.energy = energy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getLevel() {
        return level;
    }

    public long getExperience() {
        return experience;
    }

    public long getCoins() {
        return coins;
    }

    public int getEnergy() {
        return energy;
    }
}