package com.cyberheist.achievement;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.reward.Reward;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One permanent milestone.
 *
 * <p>Entirely data: a code, a name, a category, the counter it watches and the
 * number that counter has to reach. There is no behaviour here and no per-achievement
 * Java class, so retuning a milestone or adding one is a migration rather than a
 * code change.
 *
 * <p>{@code requirementValue} is the only thing that decides whether it unlocks,
 * and it is read from this row by the server. No request anywhere carries an
 * achievement id, a requirement or a reward, so none of these three values can be
 * influenced by a client.
 */
@Entity
@Table(name = "achievements")
public class Achievement extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private AchievementCategory category;

    /** Which counter this milestone watches. Never evaluated as an expression. */
    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type", nullable = false, length = 32)
    private AchievementRequirement requirement;

    /** The value that counter must reach. Strictly positive, so nothing unlocks for free. */
    @Column(name = "requirement_value", nullable = false)
    private int requirementValue;

    @Column(name = "xp_reward", nullable = false)
    private long xpReward;

    @Column(name = "coin_reward", nullable = false)
    private long coinReward;

    /** Decorative glyph for the gallery. Has no effect on unlocking. */
    @Column(name = "icon", nullable = false, length = 16)
    private String icon;

    /** Display order within a category. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Achievement() {
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

    public AchievementCategory getCategory() {
        return category;
    }

    public AchievementRequirement getRequirement() {
        return requirement;
    }

    public int getRequirementValue() {
        return requirementValue;
    }

    public long getXpReward() {
        return xpReward;
    }

    public long getCoinReward() {
        return coinReward;
    }

    public String getIcon() {
        return icon;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * What this milestone pays, as the existing reward type.
     *
     * <p>Built here and handed to {@code RewardService} unchanged, so an achievement
     * reward is indistinguishable from a mission reward once it reaches the reward
     * pipeline: same bonuses, same level curve, same skill points.
     */
    public Reward reward() {
        return new Reward(xpReward, coinReward);
    }

    /** Whether a measured value satisfies this milestone. */
    public boolean isSatisfiedBy(long measured) {
        return measured >= requirementValue;
    }
}
