package com.cyberheist.puzzle;

import com.cyberheist.common.AuditableEntity;
import com.cyberheist.mission.MissionDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One generated puzzle, owned by one player, belonging to one mission.
 *
 * <p>The row stores everything needed to <em>validate</em> a submission - type,
 * difficulty and the {@code seed} the challenge was generated from - and
 * deliberately nothing that would reveal the answer. Re-deriving the challenge
 * from the seed yields the expected answer, so there is no plaintext to leak
 * and no hash to crack.
 *
 * <p>{@code puzzleId} is unique across the table, so a puzzle can be addressed
 * by exactly one id, and {@code status} moves out of {@code ACTIVE} exactly
 * once. Those two facts are what make duplicate submission and duplicate
 * rewards impossible.
 *
 * <p>This entity is mutated only by {@code PuzzleService} and
 * {@code MissionService}; puzzle providers never see it.
 */
@Entity
@Table(name = "puzzle_attempts")
public class PuzzleAttempt extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "mission_id", nullable = false)
    private UUID missionId;

    /** Public instance identifier handed to the client. Unique across the table. */
    @Column(name = "puzzle_id", nullable = false, unique = true)
    private UUID puzzleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "puzzle_type", nullable = false, length = 16)
    private PuzzleType puzzleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private MissionDifficulty difficulty;

    /** Seed the challenge was generated from. Never sufficient on its own to cheat. */
    @Column(name = "seed", nullable = false)
    private long seed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PuzzleAttemptStatus status;

    /** Server time at which the challenge was handed to the player. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Server time after which a submission is refused. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Server time at which the answer was accepted or rejected. */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** 1 for the first puzzle generated on a mission, then incrementing. */
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    protected PuzzleAttempt() {
        // for JPA
    }

    public PuzzleAttempt(UUID id,
                         UUID userId,
                         UUID missionId,
                         UUID puzzleId,
                         PuzzleType puzzleType,
                         MissionDifficulty difficulty,
                         long seed,
                         Instant startedAt,
                         Instant expiresAt,
                         int attemptNumber) {
        this.id = id;
        this.userId = userId;
        this.missionId = missionId;
        this.puzzleId = puzzleId;
        this.puzzleType = puzzleType;
        this.difficulty = difficulty;
        this.seed = seed;
        this.status = PuzzleAttemptStatus.ACTIVE;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.attemptNumber = attemptNumber;
    }

    /**
     * Records the outcome of the single allowed submission.
     *
     * @throws IllegalStateException if this puzzle was already submitted, which
     *                               would mean the one-submission rule was bypassed
     */
    public void submit(PuzzleAttemptStatus outcome, Instant now) {
        if (!status.isSubmittable()) {
            throw new IllegalStateException("Puzzle " + puzzleId + " has already been answered");
        }
        this.status = outcome;
        this.submittedAt = now;
    }

    /** Closes an unanswered puzzle whose window has passed. Idempotent. */
    public void expire(Instant now) {
        if (status.isSubmittable()) {
            this.status = PuzzleAttemptStatus.EXPIRED;
            this.submittedAt = now;
        }
    }

    /**
     * True when {@code now} is at or past {@link #expiresAt}.
     *
     * <p>Called with the server clock only.
     */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getMissionId() {
        return missionId;
    }

    public UUID getPuzzleId() {
        return puzzleId;
    }

    public PuzzleType getPuzzleType() {
        return puzzleType;
    }

    public MissionDifficulty getDifficulty() {
        return difficulty;
    }

    public long getSeed() {
        return seed;
    }

    public PuzzleAttemptStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }
}