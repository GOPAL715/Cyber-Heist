package com.cyberheist.skill;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * How far a player has taken one skill.
 *
 * <p><strong>A missing row means level 0.</strong> Rows are created on the first
 * upgrade and never for a skill the player has not touched, so the table holds
 * only what was actually learned rather than a row per player per skill. It also
 * means a skill added in a later phase needs no backfill: existing players
 * simply have no row for it.
 *
 * <p>{@code currentLevel} is therefore always at least 1, which the database
 * enforces.
 */
@Entity
@Table(name = "player_skills")
public class PlayerSkill extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "current_level", nullable = false)
    private int currentLevel;

    protected PlayerSkill() {
        // for JPA
    }

    public PlayerSkill(UUID id, UUID userId, UUID skillId, int currentLevel) {
        this.id = id;
        this.userId = userId;
        this.skillId = skillId;
        this.currentLevel = currentLevel;
    }

    /** Raises the level. Only ever called by {@code SkillTreeService}. */
    public void advanceTo(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("Skill level must be positive");
        }
        if (level < this.currentLevel) {
            // Skills only ever go up. A decrease would silently refund points
            // the player no longer has, so it is refused rather than allowed.
            throw new IllegalArgumentException("Skill level must not decrease");
        }
        this.currentLevel = level;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }
}