package com.cyberheist.daily;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A player's activity streak, and the milestones already paid for it.
 */
public interface PlayerStreakRepository extends JpaRepository<PlayerStreak, UUID> {

    Optional<PlayerStreak> findByUserId(UUID userId);
}
