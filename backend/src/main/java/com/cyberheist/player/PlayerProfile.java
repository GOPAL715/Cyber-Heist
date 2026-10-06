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

    /**
     * Lifetime coins credited, which is not the same thing as the balance.
     *
     * <p>{@link #coins} falls when coins are spent, so it cannot answer a question
     * about totals. Added by Phase 7 for the economy achievements and the
     * {@code COINS_EARNED} daily requirement, and like every other balance field it
     * has no public setter: {@link #addCoins} is the only writer.
     */
    @Column(name = "coins_earned", nullable = false)
    private long coinsEarned;

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

    /**
     * Unspent skill points, granted one per level gained.
     *
     * <p>Added in V6. Defaults to 0 both for existing rows and for new players:
     * the column default covers the first, and the constructor below sets it for
     * the second, so no player can start with a balance they did not earn.
     *
     * <p>Like coins and energy this has no public setter. Points are only ever
     * added by {@code ProgressionService} on a level-up and only ever removed by
     * {@code SkillTreeService} spending them, so there is no request a client
     * could make to mint them.
     */
    @Column(name = "skill_points", nullable = false)
    private int skillPoints;

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
        this.skillPoints = 0;
        this.coinsEarned = 0;
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

    /**
     * Coins ever credited, for the economy milestones.
     *
     * <p>Distinct from {@link #getCoins()}: this one never falls, so it stays
     * meaningful after the balance has been spent down to nothing.
     */
    public long getCoinsEarned() {
        return coinsEarned;
    }

    public int getEnergy() {
        return energy;
    }

    public Instant getLastEnergyUpdate() {
        return lastEnergyUpdate;
    }

    public int getSkillPoints() {
        return skillPoints;
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

    /**
     * Credits coins. The only writer of the coin balance in Phase 2.
     *
     * <p>Phase 7 adds the lifetime counter here rather than beside it, and that is
     * deliberate: this method is already the single point through which every coin
     * in the game arrives - missions, bosses, achievements, daily challenges and
     * streak milestones alike all reach the profile through here. Crediting
     * {@code coinsEarned} on the same line is what makes "how much has this player
     * ever earned" exact rather than a reconstruction, and it cannot drift because
     * there is no second path that pays coins.
     */
    public void addCoins(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Coin award must not be negative");
        }
        this.coins += amount;
        this.coinsEarned += amount;
    }

    /**
     * Grants skill points, one per level gained.
     *
     * <p>Called only by {@code ProgressionService}, which is the single place a
     * level changes. The amount is {@code levelsGained}, not 1, so a single large
     * reward that crosses several thresholds grants a point for each of them
     * rather than one point for the whole jump.
     *
     * @param levelsGained how many level boundaries the reward crossed
     */
    public void addSkillPoints(int levelsGained) {
        if (levelsGained < 0) {
            throw new IllegalArgumentException("Levels gained must not be negative");
        }
        this.skillPoints += levelsGained;
    }

    /**
     * Spends skill points, refusing to go negative.
     *
     * <p>Used only by {@code SkillTreeService}, which holds the profile's
     * pessimistic lock for the duration of the unlock, so the balance tested is
     * the balance deducted from.
     *
     * @throws IllegalStateException when the player cannot afford the cost
     */
    public void spendSkillPoints(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Skill point cost must be positive");
        }
        if (this.skillPoints < amount) {
            throw new IllegalStateException("Insufficient skill points");
        }
        this.skillPoints -= amount;
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