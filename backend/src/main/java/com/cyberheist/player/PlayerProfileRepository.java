package com.cyberheist.player;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, UUID> {

    Optional<PlayerProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /**
     * Loads a profile for update, serialising concurrent energy mutations.
     *
     * <p>Energy is a read-modify-write: refresh it, then spend it. Without a
     * lock two simultaneous mission starts could both read the same balance and
     * both deduct from it, so the player would be charged for only one of two
     * missions while both were marked in progress. The pessimistic write lock
     * makes the pair atomic.
     *
     * <p>Ordering matters: this is always taken <em>before</em> the
     * mission-progress lock in the same transaction, so every path acquires
     * profile before progress and the two can never deadlock against each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlayerProfile p where p.userId = :userId")
    Optional<PlayerProfile> findByUserIdForUpdate(@Param("userId") UUID userId);
}