package com.cyberheist.player;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.progression.LevelCurve;
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

    // ---------------------------------------------------------------------
    // Trusted mutations.
    //
    // These are the only writers of level, XP, coins and energy in Phase 2.
    // They are plain Java methods with no JSON binding, so no controller and
    // no client-supplied payload can set these values directly; the reward
    // path in RewardService is the single legitimate caller.
    // ---------------------------------------------------------------------

    /**
     * Applies an experience reward and recomputes the level.
     *
     * <p>XP is cumulative, so the level is derived from total XP rather than
     * stored separately, which makes the two impossible to desynchronise.
     * A single large reward can cross several thresholds at once.
     *
     * @return the level before the reward was applied
     */
    public int addExperience(long amount, LevelCurve levelCurve) {
        if (amount < 0) {
            throw new IllegalArgumentException("Experience award must not be negative");
        }
        int previousLevel = this.level;
        this.experience += amount;
        this.level = levelCurve.levelFor(this.experience);
        return previousLevel;
    }

    /** Credits coins. The only writer of the coin balance in Phase 2. */
    public void addCoins(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Coin award must not be negative");
        }
        this.coins += amount;
    }

    /**
     * Spends energy, refusing to go negative.
     *
     * @return the energy left afterwards
     * @throws IllegalStateException when the player cannot afford the cost
     */
    public int spendEnergy(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Energy cost must not be negative");
        }
        if (this.energy < amount) {
            throw new IllegalStateException("Insufficient energy");
        }
        this.energy -= amount;
        return this.energy;
    }
}