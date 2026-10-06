package com.cyberheist.boss;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One phase of a boss: the challenge it demands, how long the player gets, and
 * how much it hurts.
 *
 * <p>{@code puzzleType} and {@code difficulty} are handed straight to
 * {@code PuzzleService}, so a phase cannot invent a challenge family — adding a
 * {@link PuzzleType} here without a provider would fail the encounter at
 * runtime rather than at boot.
 *
 * <p>{@code damageValue} is the only thing in the game that moves boss integrity,
 * and it is applied by the server from this row. No request carries a damage
 * field at all.
 */
@Entity
@Table(name = "boss_stages")
public class BossStage extends AuditableEntity {

    /** Every boss starts here and can only fall to zero. */
    public static final int MAX_INTEGRITY = 100;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "boss_id", nullable = false)
    private UUID bossId;

    @Column(name = "stage_number", nullable = false)
    private int stageNumber;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "puzzle_type", nullable = false, length = 16)
    private PuzzleType puzzleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private MissionDifficulty difficulty;

    /**
     * The window the player gets for this phase.
     *
     * <p>Overrides the provider's own window deliberately: a boss phase needs a
     * predictable budget regardless of family, and a TIMED puzzle would
     * otherwise give seconds rather than minutes.
     */
    @Column(name = "time_limit_seconds", nullable = false)
    private int timeLimitSeconds;

    @Column(name = "damage_value", nullable = false)
    private int damageValue;

    protected BossStage() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getBossId() {
        return bossId;
    }

    public int getStageNumber() {
        return stageNumber;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PuzzleType getPuzzleType() {
        return puzzleType;
    }

    public MissionDifficulty getDifficulty() {
        return difficulty;
    }

    public int getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public int getDamageValue() {
        return damageValue;
    }
}