package com.cyberheist.skill;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite key of {@link SkillPrerequisite}: the gated skill and the
 * requirement.
 *
 * <p>An embeddable rather than a surrogate id because the table's identity really
 * is the pair - {@code (skill_id, required_skill_id)} is its primary key in the
 * migration, and a generated uuid here would mean the entity and the schema
 * disagreed about what identifies an edge.
 */
@Embeddable
public class SkillPrerequisiteId implements Serializable {

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "required_skill_id", nullable = false)
    private UUID requiredSkillId;

    protected SkillPrerequisiteId() {
        // for JPA
    }

    public SkillPrerequisiteId(UUID skillId, UUID requiredSkillId) {
        this.skillId = skillId;
        this.requiredSkillId = requiredSkillId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public UUID getRequiredSkillId() {
        return requiredSkillId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SkillPrerequisiteId that)) {
            return false;
        }
        return Objects.equals(skillId, that.skillId)
                && Objects.equals(requiredSkillId, that.requiredSkillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skillId, requiredSkillId);
    }
}