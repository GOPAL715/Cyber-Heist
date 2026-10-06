package com.cyberheist.puzzle;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PuzzleAttemptRepository extends JpaRepository<PuzzleAttempt, UUID> {

    /**
     * Every puzzle ever generated for one player on one mission, oldest first.
     *
     * <p>Used to invalidate a superseded puzzle when a mission is restarted and
     * to derive the next attempt number.
     */
    List<PuzzleAttempt> findByUserIdAndMissionIdOrderByAttemptNumberAsc(UUID userId, UUID missionId);

    /**
     * Loads a puzzle for update, serialising concurrent submissions.
     *
     * <p>The pessimistic write lock is what makes the single-submission rule hold
     * under concurrency: two simultaneous submissions run one after the other,
     * so the second observes {@code SUCCEEDED} and awards nothing. Locking the
     * puzzle before the mission progress row also fixes a global lock order,
     * which is what stops two requests from deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PuzzleAttempt a where a.puzzleId = :puzzleId")
    Optional<PuzzleAttempt> findByPuzzleIdForUpdate(@Param("puzzleId") UUID puzzleId);

    /**
     * Rewrites a puzzle's window, leaving its state alone.
     *
     * <p>Exists so a test can place the window in the past and exercise the
     * expiry path without waiting real minutes, and without flipping the puzzle
     * to EXPIRED first - which would take a different branch and prove nothing
     * about how a live puzzle's clock is read. Production code never calls this.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update PuzzleAttempt a set a.startedAt = :startedAt, a.expiresAt = :expiresAt "
            + "where a.puzzleId = :puzzleId")
    int rewindow(@Param("puzzleId") UUID puzzleId,
                 @Param("startedAt") Instant startedAt,
                 @Param("expiresAt") Instant expiresAt);
}