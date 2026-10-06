package com.cyberheist.daily;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Which streak milestones a player has already been paid.
 *
 * <p>{@code existsByUserIdAndMilestoneDays} is the check that makes a repeat payment
 * impossible, and the unique constraint behind it is what makes that check true under
 * concurrency rather than only in the common case.
 */
public interface StreakMilestoneAwardRepository extends JpaRepository<StreakMilestoneAward, UUID> {

    boolean existsByUserIdAndMilestoneDays(UUID userId, int milestoneDays);

    List<StreakMilestoneAward> findByUserIdOrderByMilestoneDaysAsc(UUID userId);

    /**
     * Every milestone this account has been paid for.
     *
     * <p>Exists so the test support can total what the streak system has paid a
     * player, which is what lets a Phase 1-6 balance assertion account for the
     * streak component without hard-coding milestone amounts.
     */
    List<StreakMilestoneAward> findByUserId(UUID userId);
}
