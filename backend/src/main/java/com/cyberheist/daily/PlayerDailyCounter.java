package com.cyberheist.daily;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A player's total for one {@link DailyMetric} on one business date.
 *
 * <p>Keyed by date, which is the whole design. Because the row is qualified by the
 * day it belongs to, yesterday's total is simply never read again: there is no
 * midnight job, no per-player timer and no reset sweep. A player who returns after
 * a week starts accumulating into a new row and their old rows sit there inert.
 *
 * <p>The unique constraint on (user, date, metric) is what makes the increment
 * idempotent under concurrency: two threads crediting the same event on the same day
 * serialise on the same row, and the second one finds a row to add to rather than
 * creating a second one.
 */
@Entity
@Table(name = "player_daily_counters")
public class PlayerDailyCounter extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, length = 32)
    private DailyMetric metric;

    @Column(name = "metric_value", nullable = false)
    private long value;

    protected PlayerDailyCounter() {
        // for JPA
    }

    public static PlayerDailyCounter empty(UUID userId, LocalDate date, DailyMetric metric) {
        PlayerDailyCounter row = new PlayerDailyCounter();
        row.id = UUID.randomUUID();
        row.userId = userId;
        row.businessDate = date;
        row.metric = metric;
        row.value = 0L;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public DailyMetric getMetric() {
        return metric;
    }

    public long getValue() {
        return value;
    }

    /**
     * Adds to the day's total, refusing to go backwards.
     *
     * <p>Every caller adds a real, already-committed amount: a mission's XP, a
     * boss's payout. Nothing can subtract, so a daily objective can never be
     * un-achieved by a purchase or a death.
     */
    public void add(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Daily counter increment must not be negative");
        }
        this.value += amount;
    }

    /** Test-only helper: places a total without going through a real event. */
    public void forceValue(long newValue) {
        this.value = Math.max(0L, newValue);
    }
}
