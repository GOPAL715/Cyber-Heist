package com.cyberheist.mission;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MissionProgressRepository extends JpaRepository<MissionProgress, UUID> {

    List<MissionProgress> findByUserId(UUID userId);

    Optional<MissionProgress> findByUserIdAndMissionId(UUID userId, UUID missionId);

    /**
     * Loads a progress row for update, serialising concurrent completions.
     *
     * <p>The pessimistic write lock means two simultaneous completion requests
     * for the same mission run one after the other: the second sees the row the
     * first committed, and therefore refuses to award rewards twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MissionProgress p where p.userId = :userId and p.missionId = :missionId")
    Optional<MissionProgress> findByUserIdAndMissionIdForUpdate(@Param("userId") UUID userId,
                                                              @Param("missionId") UUID missionId);

    /**
     * Missions this player has finished, in total.
     *
     * <p>Added by Phase 7 to derive the mission milestones. Counting
     * {@code COMPLETED} rows is counting <em>distinct</em> missions, because the
     * table holds at most one row per (player, mission): replaying a mission to farm
     * a counter is not possible, which is exactly what a derived count buys over an
     * incremented one.
     */
    long countByUserIdAndStatus(UUID userId, MissionStatus status);

    /**
     * Missions finished since an instant, for a same-day daily objective.
     *
     * <p>Half-open on the caller's side: pass the business day's start.
     */
    @Query("select count(p) from MissionProgress p where p.userId = :userId "
            + "and p.status = 'COMPLETED' and p.completedAt >= :since")
    long countCompletedSince(@Param("userId") UUID userId,
                             @Param("since") java.time.Instant since);
}