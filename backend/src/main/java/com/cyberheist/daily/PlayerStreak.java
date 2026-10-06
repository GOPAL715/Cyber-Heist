package com.cyberheist.daily;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A player's consecutive-day activity record.
 *
 * <p>The streak counts <em>business</em> days, resolved by
 * {@code BusinessCalendar} from the server clock. A device clock, a timezone or a
 * request body therefore has no influence on it: the only inputs are the server's
 * own idea of today's date and the date it recorded last time.
 *
 * <p>Updating is a pure function of "what did we last see, and what is today", so
 * the interesting logic lives in {@link #recordActivity} and is testable without a
 * database.
 */
@Entity
@Table(name = "player_streaks")
public class PlayerStreak extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    @Column(name = "longest_streak", nullable = false)
    private int longestStreak;

    /**
     * The last business day this player was seen.
     *
     * <p>A date rather than an instant, because the question being answered is
     * "was yesterday the last day", which is a calendar question.
     */
    @Column(name = "last_activity_date")
    private LocalDate lastActivityDate;

    protected PlayerStreak() {
        // for JPA
    }

    /** A fresh record for a player seen for the first time today. */
    public static PlayerStreak firstSeen(UUID userId, LocalDate today) {
        PlayerStreak row = new PlayerStreak();
        row.id = UUID.randomUUID();
        row.userId = userId;
        row.currentStreak = 1;
        row.longestStreak = 1;
        row.lastActivityDate = today;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getCurrentStreak() {
        return currentStreak;
    }

    public int getLongestStreak() {
        return longestStreak;
    }

    public LocalDate getLastActivityDate() {
        return lastActivityDate;
    }

    /**
     * Records activity on {@code today} and returns the streak that resulted.
     *
     * <p>Three cases, and only three:
     * <ul>
     *   <li>same day as last time - nothing changes, which is what stops a player
     *       who reloads the page forty times from inflating their streak;</li>
     *   <li>the day immediately after - the streak extends;</li>
     *   <li>anything else, including a date that is somehow earlier than the last
     *       recorded one - the streak restarts from one, rather than being
     *       extended by a gap or wound backwards.</li>
     * </ul>
     */
    public int recordActivity(LocalDate today) {
        if (lastActivityDate == null) {
            currentStreak = 1;
        } else if (lastActivityDate.equals(today)) {
            // Already counted today. Deliberately a no-op.
            return currentStreak;
        } else if (lastActivityDate.plusDays(1).equals(today)) {
            currentStreak = currentStreak + 1;
        } else {
            currentStreak = 1;
        }
        lastActivityDate = today;
        longestStreak = Math.max(longestStreak, currentStreak);
        return currentStreak;
    }

    /** Whether this record already covers {@code today}. */
    public boolean covers(LocalDate today) {
        return today.equals(lastActivityDate);
    }

    /**
     * A past date on which this player was active.
     *
     * <p>Test-only helper, mirroring the existing pattern of rewriting a persisted
     * timestamp to exercise a boundary without waiting for real days to pass.
     */
    public void rewindLastActivityDate(LocalDate date) {
        this.lastActivityDate = date;
    }

    /** Test-only helper: forces a streak length without walking a calendar. */
    public void forceStreak(int current, int longest, Instant now) {
        this.currentStreak = current;
        this.longestStreak = Math.max(longest, current);
        this.lastActivityDate = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
