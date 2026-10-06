package com.cyberheist.boss;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.mission.MissionDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A boss definition: what it is, what it demands, and what it pays.
 *
 * <p>Server-owned. Written by migration alone — no endpoint creates, edits or
 * retires a boss — so a client has nothing it could offer to influence. What the
 * player controls is only whether to attempt it.
 *
 * <p>{@code energyCost} is the <em>base</em> charge. Equipment and skill energy
 * efficiency may reduce the amount actually taken, which is applied by
 * {@code PlayerBonusService} and never here.
 */
@Entity
@Table(name = "bosses")
public class Boss extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private MissionDifficulty difficulty;

    @Column(name = "required_level", nullable = false)
    private int requiredLevel;

    @Column(name = "energy_cost", nullable = false)
    private int energyCost;

    @Column(name = "stage_count", nullable = false)
    private int stageCount;

    @Column(name = "xp_reward", nullable = false)
    private long xpReward;

    @Column(name = "coin_reward", nullable = false)
    private long coinReward;

    @Column(name = "cooldown_victory_minutes", nullable = false)
    private int cooldownVictoryMinutes;

    @Column(name = "cooldown_defeat_minutes", nullable = false)
    private int cooldownDefeatMinutes;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Boss() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public MissionDifficulty getDifficulty() {
        return difficulty;
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }

    public int getEnergyCost() {
        return energyCost;
    }

    public int getStageCount() {
        return stageCount;
    }

    public long getXpReward() {
        return xpReward;
    }

    public long getCoinReward() {
        return coinReward;
    }

    public int getCooldownVictoryMinutes() {
        return cooldownVictoryMinutes;
    }

    public int getCooldownDefeatMinutes() {
        return cooldownDefeatMinutes;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * The cooldown that applies after an outcome.
     *
     * <p>A defeat is forgiven far sooner than a victory: losing should cost the
     * player time, not the run, while a win is the thing worth farming.
     */
    public int cooldownMinutesFor(EncounterStatus outcome) {
        return outcome == EncounterStatus.VICTORY ? cooldownVictoryMinutes : cooldownDefeatMinutes;
    }
}