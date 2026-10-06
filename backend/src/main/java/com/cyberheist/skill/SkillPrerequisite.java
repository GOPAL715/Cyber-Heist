package com.cyberheist.skill;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A dependency edge: {@code skill} cannot be taken until
 * {@code requiredSkill} has reached {@code requiredLevel}.
 *
 * <p>Modelled as an edge rather than a column on {@link Skill} because a skill
 * may have several prerequisites, and because the direction is worth being
 * explicit about: {@code skillId} is the gated skill, not the requirement.
 *
 * <p>The key is the pair, matching the table's primary key, so a duplicate edge is
 * rejected by the database rather than by a lookup first.
 */
@Entity
@Table(name = "skill_prerequisites")
public class SkillPrerequisite {

    @EmbeddedId
    private SkillPrerequisiteId id;

    @Column(name = "required_level", nullable = false)
    private int requiredLevel;

    protected SkillPrerequisite() {
        // for JPA
    }

    /** The gated skill. */
    public UUID getSkillId() {
        return id.getSkillId();
    }

    /** The skill that must be reached first. */
    public UUID getRequiredSkillId() {
        return id.getRequiredSkillId();
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }
}