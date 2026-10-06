package com.cyberheist.daily;

import com.cyberheist.reward.Reward;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The frozen set of objectives for one business date.
 *
 * <p>A copy rather than a view onto {@link DailyChallengeDefinition}, and that
 * duplication is deliberate. If today's rows referenced the pool directly, editing a
 * requirement mid-day would silently change what a player is being asked for and
 * could make an already-completed objective unreachable. Copying the fields means
 * once a date is materialised, that day's content is fixed.
 *
 * <p>The {@code UNIQUE (challenge_date, code)} constraint means the same objective
 * can never be drawn twice on one date, whatever the selection code does.
 */
@Entity
@Table(name = "daily_challenges")
public class DailyChallenge {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** The business date this row belongs to. Decided by the server, never the client. */
    @Column(name = "challenge_date", nullable = false)
    private LocalDate challengeDate;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DailyChallenge() {
        // for JPA
    }

    /** Freezes a definition into a concrete objective for {@code date}. */
    public static DailyChallenge forDate(LocalDate date, DailyChallengeDefinition definition,
                                         Instant now) {
        DailyChallenge row = new DailyChallenge();
        row.id = UUID.randomUUID();
        row.challengeDate = date;
        row.code = definition.getCode();
        row.definitionId = definition.getId();
        row.title = definition.getTitle();
        row.description = definition.getDescription();
        row.requirement = definition.getRequirement();
        row.requirementValue = definition.getRequirementValue();
        row.xpReward = definition.getXpReward();
        row.coinReward = definition.getCoinReward();
        row.createdAt = now;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getChallengeDate() {
        return challengeDate;
    }

    public String getCode() {
        return code;
    }

    public UUID getDefinitionId() {
        return definitionId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** What completing this objective pays, through the shared reward pipeline. */
    public Reward reward() {
        return new Reward(xpReward, coinReward);
    }
}
