package com.cyberheist.achievement;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One player's state against one milestone.
 *
 * <p>{@code progress} is a cache of the last measured counter, kept so that listing
 * thirty achievements does not have to recount thirty times. It is never the
 * authority: every evaluation re-measures from the real tables and overwrites this
 * value, so a stale or wrong figure here cannot unlock anything.
 *
 * <p>Progress is clamped to the requirement on the way in, which is why a bar in
 * the UI can never render as 140/100.
 *
 * <p>The class has no way to be un-unlocked. There is no {@code revoke}, and
 * {@link #unlock} refuses to run twice, so an earned milestone stays earned for the
 * lifetime of the account even if the catalogue is later retuned or a boss is
 * retired.
 */
@Entity
@Table(name = "player_achievements")
public class PlayerAchievement extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "achievement_id", nullable = false)
    private UUID achievementId;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "unlocked", nullable = false)
    private boolean unlocked;

    @Column(name = "unlocked_at")
    private Instant unlockedAt;

    protected PlayerAchievement() {
        // for JPA
    }

    /**
     * A tracked-but-not-yet-earned milestone.
     *
     * <p>Progress starts at zero rather than at the measured value; the caller's
     * first {@link #record} carries the real number.
     */
    public static PlayerAchievement tracked(UUID userId, Achievement achievement) {
        PlayerAchievement row = new PlayerAchievement();
        row.id = UUID.randomUUID();
        row.userId = userId;
        row.achievementId = achievement.getId();
        row.progress = 0;
        row.unlocked = false;
        row.unlockedAt = null;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAchievementId() {
        return achievementId;
    }

    public int getProgress() {
        return progress;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }

    /**
     * Records a freshly measured value, clamped to the requirement.
     *
     * <p>Called on every evaluation whether or not anything changed, so a player who
     * was already past a threshold when it was seeded is credited on their next read
     * instead of being locked out.
     */
    public void record(long measured, Achievement achievement) {
        if (unlocked) {
            // An unlocked milestone keeps its recorded value. Lowering it would
            // make a completed entry look partly undone if a counter were ever
            // revised.
            return;
        }
        this.progress = clamp(measured, achievement.getRequirementValue());
    }

    /**
     * Marks the milestone earned.
     *
     * @return {@code true} if this call performed the unlock, {@code false} if it
     *         was already unlocked. The return value is what makes the reward
     *         decision safe to make inline: only a genuine transition pays.
     */
    public boolean unlock(Achievement achievement, Instant now) {
        if (unlocked) {
            return false;
        }
        this.progress = achievement.getRequirementValue();
        this.unlocked = true;
        this.unlockedAt = now;
        return true;
    }

    /**
     * Progress as reported to the client.
     *
     * <p>Clamped again on the way out so that a row written before a catalogue
     * retune, which lowered a requirement, cannot render as over-complete.
     */
    public int reportedProgress(Achievement achievement) {
        return clamp(progress, achievement.getRequirementValue());
    }

    private static int clamp(long measured, int requirement) {
        if (measured <= 0) {
            return 0;
        }
        return (int) Math.min(measured, requirement);
    }
}
