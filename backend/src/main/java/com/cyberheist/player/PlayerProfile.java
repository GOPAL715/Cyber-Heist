package com.cyberheist.player;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.progression.LevelCurve;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
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

    /**
     * Server timestamp of the last energy accounting.
     *
     * <p>Regeneration is computed lazily from this value; it is written only by
     * the backend, never from client input.
     */
    @Column(name = "last_energy_update", nullable = false)
    private Instant lastEnergyUpdate;

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
        this.lastEnergyUpdate = Instant.now();
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

    public Instant getLastEnergyUpdate() {
        return lastEnergyUpdate;
    }

    /**
     * Re-anchors the energy clock.
     *
     * <p>Only {@code EnergyService} calls this, and only to repair a missing or
     * future-dated stamp. Ordinary advancement happens inside
     * {@link #applyRegeneration} so the carried-forward fraction is preserved.
     */
    public void setLastEnergyUpdate(Instant lastEnergyUpdate) {
        this.lastEnergyUpdate = lastEnergyUpdate;
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
     * Spends coins, refusing to go negative.
     *
     * <p>Used only by {@code ShopService} inside a locked purchase transaction,
     * so the balance tested is the balance deducted from. Callers must hold the
     * pessimistic profile lock first (see {@code PlayerProfileRepository}).
     *
     * @throws IllegalStateException when the player cannot afford the price
     */
    public void spendCoins(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Coin price must not be negative");
        }
        if (this.coins < amount) {
            throw new IllegalStateException("Insufficient coins");
        }
        this.coins -= amount;
    }

    /**
     * Spends energy, refusing to go negative.
     *
     * <p>The caller is expected to have refreshed regeneration first (see
     * {@code EnergyService}), so the balance observed here is current.
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

    /**
     * Applies lazily computed regeneration.
     *
     * <p>Called only by {@code EnergyService}, which owns the interval math and
     * the server clock. The timestamp advances by exactly the whole intervals
     * consumed, never to "now" outright, so fractional elapsed time is carried
     * forward instead of being silently dropped.
     *
     * @param regenerated   whole units earned since the last accounting
     * @param consumed      the elapsed time those units account for
     * @param maximum       hard cap; energy is clamped to it
     * @return the energy afterwards
     */
    public int applyRegeneration(int regenerated, java.time.Duration consumed, int maximum) {
        if (regenerated < 0) {
            throw new IllegalArgumentException("Regenerated energy must not be negative");
        }
        if (maximum < 0) {
            throw new IllegalArgumentException("Maximum energy must not be negative");
        }
        if (this.lastEnergyUpdate != null && consumed != null
                && !consumed.isNegative() && !consumed.isZero()) {
            this.lastEnergyUpdate = this.lastEnergyUpdate.plus(consumed);
        }
        // Clamped on both sides: the cap is a hard rule, and so is never
        // negative, even if a caller ever tried to credit a negative balance.
        this.energy = Math.min(maximum, Math.max(0, this.energy + regenerated));
        return this.energy;
    }
}