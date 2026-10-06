package com.cyberheist.achievement;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A player's milestone state.
 *
 * <p>The lock matters more here than in most of the codebase. Two concurrent
 * completions can both decide an achievement is due; taking the profile lock first
 * and then this one serialises them, and the unique constraint on
 * (user, achievement) is the backstop if that ordering is ever disturbed.
 */
public interface PlayerAchievementRepository extends JpaRepository<PlayerAchievement, UUID> {

    List<PlayerAchievement> findByUserId(UUID userId);

    Optional<PlayerAchievement> findByUserIdAndAchievementId(UUID userId, UUID achievementId);

    boolean existsByUserIdAndAchievementId(UUID userId, UUID achievementId);

    List<PlayerAchievement> findByUserIdAndAchievementIdIn(UUID userId, Collection<UUID> achievementIds);

    /**
     * The most recent unlocks, newest first, for the "recent" list.
     *
     * <p>Always bounded by the caller. An unbounded "give me everything the player
     * has ever unlocked" query is exactly the shape that turns into a table scan the
     * day the catalogue grows.
     */
    @Query("select pa from PlayerAchievement pa where pa.userId = :userId and pa.unlocked = true "
            + "order by pa.unlockedAt desc")
    List<PlayerAchievement> findRecentUnlocks(@Param("userId") UUID userId, Pageable pageable);

    @Query("select count(pa) from PlayerAchievement pa where pa.userId = :userId and pa.unlocked = true")
    long countUnlocked(@Param("userId") UUID userId);

    /**
     * Milestones unlocked at or after a moment, used by the daily-count achievement.
     */
    @Query("select count(pa) from PlayerAchievement pa where pa.userId = :userId "
            + "and pa.unlocked = true and pa.unlockedAt >= :since")
    long countUnlocksSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
