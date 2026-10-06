package com.cyberheist.daily;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A record that a streak milestone has been paid.
 *
 * <p>Exists purely as an idempotency marker. The reward table would already prevent
 * a double payment inside one transaction, but a player who reaches a 7-day streak,
 * breaks it, and builds another would be paid a second time - which is either a bug
 * or a design decision, and for Phase 7 it is neither wanted nor acceptable.
 *
 * <p>{@code UNIQUE (user_id, milestone_days)} settles it at the database level: a
 * milestone can be recorded once for the lifetime of the account, so no sequence of
 * streaks can pay it twice.
 */
@Entity
@Table(name = "streak_milestone_awards")
public class StreakMilestoneAward {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "milestone_days", nullable = false)
    private int milestoneDays;

    @Column(name = "xp_awarded", nullable = false)
    private long xpAwarded;

    @Column(name = "coin_awarded", nullable = false)
    private long coinAwarded;

    @Column(name = "awarded_at", nullable = false)
    private Instant awardedAt;

    protected StreakMilestoneAward() {
        // for JPA
    }

    public StreakMilestoneAward(UUID userId, int milestoneDays, long xpAwarded,
                                long coinAwarded, Instant awardedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.milestoneDays = milestoneDays;
        this.xpAwarded = xpAwarded;
        this.coinAwarded = coinAwarded;
        this.awardedAt = awardedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getMilestoneDays() {
        return milestoneDays;
    }

    public long getXpAwarded() {
        return xpAwarded;
    }

    public long getCoinAwarded() {
        return coinAwarded;
    }

    public Instant getAwardedAt() {
        return awardedAt;
    }
}
