package com.cyberheist.daily;

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
 * One authored objective in the pool the daily rotation draws from.
 *
 * <p>Authored rather than generated, for a specific reason: a randomly assembled
 * requirement can be impossible. "Defeat 3 bosses" on a fresh account, or "earn 900
 * XP" in a single day, are both reachable in principle and absurd in practice. Every
 * requirement here is one a player can actually clear today, which is only
 * guaranteed because a human wrote it.
 *
 * <p>The pool is deliberately larger than one day's set, so the rotation repeats on
 * a cycle rather than showing the same three forever.
 */
@Entity
@Table(name = "daily_challenge_definitions")
public class DailyChallengeDefinition extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type", nullable = false, length = 32)
    private DailyMetric requirement;

    @Column(name = "requirement_value", nullable = false)
    private int requirementValue;

    @Column(name = "xp_reward", nullable = false)
    private long xpReward;

    @Column(name = "coin_reward", nullable = false)
    private long coinReward;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected DailyChallengeDefinition() {
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

    public DailyMetric getRequirement() {
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

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    /** What completing this objective pays, through the shared reward pipeline. */
    public Reward reward() {
        return new Reward(xpReward, coinReward);
    }
}
