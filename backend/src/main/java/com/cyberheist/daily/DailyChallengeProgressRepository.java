package com.cyberheist.daily;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A player's progress toward today's objectives.
 *
 * <p>Nothing here accepts a value from a caller. {@code findByUserId} and
 * {@code findByUserIdAndDailyChallengeId} are the only ways in, so there is no query
 * a controller could use to read another player's progress even by accident.
 */
public interface DailyChallengeProgressRepository extends JpaRepository<DailyChallengeProgress, UUID> {

    List<DailyChallengeProgress> findByUserId(UUID userId);

    Optional<DailyChallengeProgress> findByUserIdAndDailyChallengeId(UUID userId, UUID challengeId);

    /**
     * Objectives this player has completed on or after a moment.
     *
     * <p>Drives the lifetime daily-count achievement, and is deliberately derived
     * from the completion rows rather than from a counter, so it cannot drift.
     */
    @Query("select p from DailyChallengeProgress p where p.userId = :userId and p.completed = true "
            + "and p.completedAt >= :since")
    List<DailyChallengeProgress> findCompletedSince(@Param("userId") UUID userId,
                                                    @Param("since") java.time.Instant since);

    long countByUserIdAndCompletedTrue(UUID userId);
}
