package com.cyberheist.daily;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One player's progress toward one objective on one date.
 *
 * <p>The value here is a copy of a {@link PlayerDailyCounter}, written by the server
 * when it evaluates the day. No endpoint accepts it: there is no request body that
 * could state a progress figure, a completion flag or a reward, which is what makes
 * "earn 100 XP today" something a client cannot simply declare it has done.
 *
 * <p>{@link #complete} returns whether <em>this</em> call was the one that finished
 * the objective. A re-evaluation after completion finds it already complete, gets
 * {@code false} back and pays nothing, so the reward is granted exactly once no
 * matter how often progress is recomputed.
 */
@Entity
@Table(name = "daily_challenge_progress")
public class DailyChallengeProgress extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "daily_challenge_id", nullable = false)
    private UUID dailyChallengeId;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DailyChallengeProgress() {
        // for JPA
    }

    /** A zero-progress row, created lazily the first time a player opens the day. */
    public static DailyChallengeProgress untouched(UUID userId, DailyChallenge challenge) {
        DailyChallengeProgress row = new DailyChallengeProgress();
        row.id = UUID.randomUUID();
        row.userId = userId;
        row.dailyChallengeId = challenge.getId();
        row.progress = 0;
        row.completed = false;
        row.completedAt = null;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getDailyChallengeId() {
        return dailyChallengeId;
    }

    public int getProgress() {
        return progress;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Copies a freshly measured total in, clamped to the requirement.
     *
     * <p>A no-op once complete, so the display never walks backwards if a counter is
     * read at a different moment than the one that completed it.
     */
    public void record(long measured, DailyChallenge challenge) {
        if (completed) {
            return;
        }
        long bounded = Math.max(0L, Math.min(measured, challenge.getRequirementValue()));
        this.progress = (int) bounded;
    }

    /**
     * Marks the objective complete if the measured total satisfies it.
     *
     * @return {@code true} only for the call that actually completed it, so the
     *         reward decision can be made on this return value alone
     */
    public boolean completeIfSatisfied(long measured, DailyChallenge challenge, Instant now) {
        if (completed) {
            return false;
        }
        record(measured, challenge);
        if (progress < challenge.getRequirementValue()) {
            return false;
        }
        this.completed = true;
        this.completedAt = now;
        return true;
    }

    /** Progress as reported to the client, clamped again for the same reason as achievements. */
    public int reportedProgress(DailyChallenge challenge) {
        long bounded = Math.max(0L, Math.min(progress, challenge.getRequirementValue()));
        return (int) bounded;
    }
}
