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
 * One generated puzzle, owned by one player, belonging to one mission
 * <em>or</em> one boss encounter.
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
 * <h2>Ownership (Phase 6)</h2>
 * Phase 3 assumed every puzzle belonged to a mission. Boss encounters broke that
 * assumption, so the row now names exactly one owner: {@code missionId} for a
 * mission puzzle, {@code bossEncounterId} for a boss stage. The database enforces
 * that exactly one is set, which is what stops a boss puzzle being submitted to
 * a mission endpoint — or the reverse — even if some future code forgets to
 * check.
 *
 * <p>{@code attemptNumber} means the mission attempt number, or the boss stage
 * number, depending on the owner.
 *
 * <p>This entity is mutated only by {@code PuzzleService},
 * {@code MissionService} and {@code BossEncounterService}; puzzle providers never
 * see it.
 */
@Entity
@Table(name = "puzzle_attempts")
public class PuzzleAttempt extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Null for a boss stage attempt. Exactly one owner column is set. */
    @Column(name = "mission_id")
    private UUID missionId;

    /** Null for a mission attempt. Exactly one owner column is set. */
    @Column(name = "boss_encounter_id")
    private UUID bossEncounterId;

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
        this(id, userId, missionId, null, puzzleId, puzzleType, difficulty, seed,
                startedAt, expiresAt, attemptNumber);
    }

    /** A puzzle generated for one phase of a boss encounter. */
    public static PuzzleAttempt forBossStage(UUID id,
                                             UUID userId,
                                             UUID bossEncounterId,
                                             UUID puzzleId,
                                             PuzzleType puzzleType,
                                             MissionDifficulty difficulty,
                                             long seed,
                                             Instant startedAt,
                                             Instant expiresAt,
                                             int stageNumber) {
        return new PuzzleAttempt(id, userId, null, bossEncounterId, puzzleId, puzzleType,
                difficulty, seed, startedAt, expiresAt, stageNumber);
    }

    private PuzzleAttempt(UUID id,
                          UUID userId,
                          UUID missionId,
                          UUID bossEncounterId,
                          UUID puzzleId,
                          PuzzleType puzzleType,
                          MissionDifficulty difficulty,
                          long seed,
                          Instant startedAt,
                          Instant expiresAt,
                          int attemptNumber) {
        if ((missionId == null) == (bossEncounterId == null)) {
            // Mirrors the table's CHECK constraint, so a programming mistake
            // fails here rather than at flush time with an opaque message.
            throw new IllegalArgumentException(
                    "A puzzle attempt belongs to exactly one mission or boss encounter");
        }
        this.id = id;
        this.userId = userId;
        this.missionId = missionId;
        this.bossEncounterId = bossEncounterId;
        this.puzzleId = puzzleId;
        this.puzzleType = puzzleType;
        this.difficulty = difficulty;
        this.seed = seed;
        this.status = PuzzleAttemptStatus.ACTIVE;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.attemptNumber = attemptNumber;
    }

    /** True when this puzzle belongs to a boss stage rather than a mission. */
    public boolean isBossPuzzle() {
        return bossEncounterId != null;
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

    /** Null for a mission puzzle. */
    public UUID getBossEncounterId() {
        return bossEncounterId;
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