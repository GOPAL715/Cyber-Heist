package com.cyberheist.skill;

import com.cyberheist.shop.ItemEffectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One level of one skill: what it costs in points and what it grants.
 *
 * <p>The balance table. Keeping cost and effect in the database rather than in a
 * Java array means re-tuning the tree is a migration, and it means
 * {@code SkillTreeService} never has to know that, say, level 4 costs two
 * points - it reads the row.
 *
 * <p>Exactly one effect per level: a level is "a little more of one thing",
 * which keeps the skill screen legible and makes the bonus aggregation a plain
 * lookup rather than a merge.
 */
@Entity
@Table(name = "skill_levels")
public class SkillLevel {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "skill_point_cost", nullable = false)
    private int skillPointCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 32)
    private ItemEffectType effectType;

    /** Percentage granted at this level, not an increment on the previous one. */
    @Column(name = "effect_value", nullable = false)
    private int effectValue;

    protected SkillLevel() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getLevel() {
        return level;
    }

    public int getSkillPointCost() {
        return skillPointCost;
    }

    public ItemEffectType getEffectType() {
        return effectType;
    }

    public int getEffectValue() {
        return effectValue;
    }
}