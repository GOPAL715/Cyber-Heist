package com.cyberheist.mission;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.puzzle.PuzzleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A mission in the catalogue.
 *
 * <p>Read-only from the player's perspective: {@code xpReward},
 * {@code coinReward} and {@code energyCost} are authoritative server values.
 * There is deliberately no endpoint that creates or edits missions, so a
 * client can never influence the reward it is about to claim.
 */
@Entity
@Table(name = "missions")
public class Mission extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private MissionCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private MissionDifficulty difficulty;

    /**
     * Which puzzle family this mission generates.
     *
     * <p>Server-owned data, exactly like the rewards: no client chooses it, and
     * the puzzle engine resolves it through its provider registry. Keeping it
     * on the mission rather than in a switch is what lets a mission be
     * re-pointed at a new puzzle type without a code change.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "puzzle_type", nullable = false, length = 16)
    private PuzzleType puzzleType;

    @Column(name = "required_level", nullable = false)
    private int requiredLevel;

    @Column(name = "xp_reward", nullable = false)
    private int xpReward;

    @Column(name = "coin_reward", nullable = false)
    private long coinReward;

    @Column(name = "energy_cost", nullable = false)
    private int energyCost;

    @Column(name = "estimated_duration_seconds", nullable = false)
    private int estimatedDurationSeconds;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Mission() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public MissionCategory getCategory() {
        return category;
    }

    public MissionDifficulty getDifficulty() {
        return difficulty;
    }

    public PuzzleType getPuzzleType() {
        return puzzleType;
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }

    public int getXpReward() {
        return xpReward;
    }

    public long getCoinReward() {
        return coinReward;
    }

    public int getEnergyCost() {
        return energyCost;
    }

    public int getEstimatedDurationSeconds() {
        return estimatedDurationSeconds;
    }

    public boolean isActive() {
        return active;
    }

    /** True when a player of {@code playerLevel} is allowed to attempt this mission. */
    public boolean isUnlockedFor(int playerLevel) {
        return playerLevel >= requiredLevel;
    }

    /** Retires a mission. Used by administration and by tests. */
    public void deactivate() {
        this.active = false;
    }

    /** Returns a retired mission to the catalogue. */
    public void activate() {
        this.active = true;
    }
}