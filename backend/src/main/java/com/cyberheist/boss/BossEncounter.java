package com.cyberheist.boss;

import com.cyberheist.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One player's run at one boss.
 *
 * <h2>The state machine</h2>
 * The only legal transition out of {@link EncounterStatus#ACTIVE} is into a
 * terminal status, and each method below is the only way to reach one:
 *
 * <pre>
 *   ACTIVE --every stage cleared--&gt; VICTORY
 *   ACTIVE --wrong answer----------&gt; DEFEATED
 *   ACTIVE --stage window closed----&gt; DEFEATED
 *   ACTIVE --encounter window shut--&gt; EXPIRED
 * </pre>
 *
 * <p>Nothing here can move backwards. That is the structural reason a boss
 * victory cannot be paid twice: once the row is terminal there is no method that
 * reopens it, so a replayed final submission has nothing to award.
 *
 * <h2>Integrity</h2>
 * Owned entirely by the server. {@link #damage} is the only mutator and it takes
 * no value from a caller — the amount comes from {@code boss_stages.damage_value}
 * and the encounter is floored at zero.
 *
 * <h2>Expiry</h2>
 * Resolved lazily from {@code expiresAt} against the server clock. There is no
 * background job: an encounter nobody looks at costs nothing, and the first
 * request that does look at it resolves the state.
 */
@Entity
@Table(name = "boss_encounters")
public class BossEncounter extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "boss_id", nullable = false)
    private UUID bossId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EncounterStatus status;

    /** The phase being faced right now. Never zero. */
    @Column(name = "current_stage", nullable = false)
    private int currentStage;

    @Column(name = "boss_integrity", nullable = false)
    private int bossIntegrity;

    /** Highest phase reached. Kept after the fact so history reads honestly. */
    @Column(name = "reached_stage", nullable = false)
    private int reachedStage;

    /** Zero unless the encounter was won; the database enforces that pairing. */
    @Column(name = "xp_awarded", nullable = false)
    private long xpAwarded;

    @Column(name = "coin_awarded", nullable = false)
    private long coinAwarded;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Server-calculated when the encounter ended. Never read from a request. */
    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    /**
     * 1 while {@code ACTIVE}, null once terminal.
     *
     * <p>Paired with {@code UNIQUE (user_id, active_marker)}, which is what makes
     * "one live encounter per player" a database guarantee rather than a hope.
     * NULLs never collide in a unique index, so finished encounters do not
     * constrain each other.
     */
    @Column(name = "active_marker")
    private Integer activeMarker;

    protected BossEncounter() {
        // for JPA
    }

    public BossEncounter(UUID id, UUID userId, UUID bossId, int stageCount, Instant now,
                         Duration window) {
        this.id = id;
        this.userId = userId;
        this.bossId = bossId;
        this.status = EncounterStatus.ACTIVE;
        this.currentStage = 1;
        this.bossIntegrity = BossStage.MAX_INTEGRITY;
        this.reachedStage = 1;
        this.xpAwarded = 0L;
        this.coinAwarded = 0L;
        this.startedAt = now;
        this.expiresAt = now.plus(window);
        this.activeMarker = 1;
        if (stageCount < 1) {
            throw new IllegalArgumentException("A boss must have at least one stage");
        }
    }

    /** True while the encounter may still be progressed. */
    public boolean isActive() {
        return status.isOpen();
    }

    /** True once the encounter can no longer change. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * Applies a phase's damage and moves to the next phase.
     *
     * @param damage    taken from {@code boss_stages.damage_value}; never a
     *                  request value
     * @param nextStage the phase now being faced, or {@code 0} when this was the
     *                  final phase
     */
    public void damage(int damage, int nextStage) {
        requireActive();
        if (damage <= 0) {
            throw new IllegalArgumentException("Boss damage must be positive");
        }
        this.bossIntegrity = Math.max(0, this.bossIntegrity - damage);
        if (nextStage > 0) {
            this.currentStage = nextStage;
            this.reachedStage = Math.max(this.reachedStage, nextStage);
        }
    }

    /**
     * Marks the boss defeated and records what it paid.
     *
     * <p>Integrity is pinned to zero rather than merely reduced, so a victory can
     * never leave a boss with more than nothing left.
     */
    public void win(long experience, long coins, Instant now) {
        requireActive();
        this.status = EncounterStatus.VICTORY;
        this.bossIntegrity = 0;
        this.completedAt = now;
        this.xpAwarded = experience;
        this.coinAwarded = coins;
        this.activeMarker = null;
    }

    /**
     * Marks the encounter lost.
     *
     * <p>A wrong answer and an expired phase window are the same outcome for the
     * player: the run is over and nothing is refunded. {@code EXPIRED} is
     * reserved for the encounter window closing, which is a different fact.
     */
    public void lose(Instant now) {
        requireActive();
        this.status = EncounterStatus.DEFEATED;
        this.failedAt = now;
        this.activeMarker = null;
    }

    /** Marks the encounter abandoned because its window closed. */
    public void expire(Instant now) {
        requireActive();
        this.status = EncounterStatus.EXPIRED;
        this.failedAt = now;
        this.activeMarker = null;
    }

    /** Records when this boss may be attempted again. */
    public void startCooldown(Instant until) {
        this.cooldownUntil = until;
    }

    /** True when {@code now} is at or past the encounter window. */
    public boolean isExpiredAt(Instant now) {
        return isActive() && !now.isBefore(expiresAt);
    }

    /** True when the player is still inside their cooldown for this boss. */
    public boolean isOnCooldownAt(Instant now) {
        return cooldownUntil != null && now.isBefore(cooldownUntil);
    }

    /** Integrity remaining. Never negative, never above the starting value. */
    public int getBossIntegrity() {
        return bossIntegrity;
    }

    /**
     * Integrity as a percentage of the starting value, for the boss bar.
     *
     * <p>Derived here rather than in the client so the bar cannot disagree with
     * the value the server uses to decide the encounter is over.
     */
    public int integrityPercent() {
        return Math.max(0, Math.min(100, bossIntegrity * 100 / BossStage.MAX_INTEGRITY));
    }

    private void requireActive() {
        if (!isActive()) {
            // The single most important invariant in the boss system: a terminal
            // encounter can never be transitioned again, which is what makes a
            // duplicate payout impossible rather than merely unlikely.
            throw new IllegalStateException(
                    "Encounter " + id + " is already " + status + " and cannot change");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getBossId() {
        return bossId;
    }

    public EncounterStatus getStatus() {
        return status;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public int getReachedStage() {
        return reachedStage;
    }

    public long getXpAwarded() {
        return xpAwarded;
    }

    public long getCoinAwarded() {
        return coinAwarded;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }
}