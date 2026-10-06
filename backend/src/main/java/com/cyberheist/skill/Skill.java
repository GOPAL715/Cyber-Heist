package com.cyberheist.skill;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A skill definition: what it is called, which branch it belongs to and how far
 * it can be taken.
 *
 * <p>Server-owned. The table is written by migration alone - there is no
 * endpoint that creates, edits or retires a skill - so the frontend has nothing
 * it could offer to influence. What the player controls is how far along they
 * have taken it, which lives in {@link PlayerSkill}.
 */
@Entity
@Table(name = "skills")
public class Skill extends AuditableEntity {

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
    @Column(name = "branch", nullable = false, length = 20)
    private SkillBranch branch;

    @Column(name = "max_level", nullable = false)
    private int maxLevel;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Skill() {
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

    public SkillBranch getBranch() {
        return branch;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public boolean isActive() {
        return active;
    }
}