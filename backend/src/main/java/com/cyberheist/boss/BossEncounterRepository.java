package com.cyberheist.boss;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BossEncounterRepository extends JpaRepository<BossEncounter, UUID> {

    /** Every encounter this player has, newest first. */
    List<BossEncounter> findByUserId(UUID userId);

    /** The player's live encounter, if there is one. */
    Optional<BossEncounter> findByUserIdAndStatus(UUID userId, EncounterStatus status);

    /**
     * Loads the player's live encounter for update.
     *
     * <p>Every progression path locks this row before writing, so two concurrent
     * submissions cannot both consume the same puzzle, and two final submissions
     * cannot both attempt the payout. Combined with the profile lock taken at
     * start, that is the whole concurrency story.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BossEncounter e where e.userId = :userId and e.status = 'ACTIVE'")
    Optional<BossEncounter> findActiveForUpdate(@Param("userId") UUID userId);

    /** History, newest first and explicitly bounded by the caller. */
    List<BossEncounter> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * The player's most recent encounter of any kind.
     *
     * <p>Used to resolve state lazily for a player who has several: the newest
     * is the one any cooldown or expiry question is about.
     */
    Optional<BossEncounter> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** The latest cooldown across all of a player's attempts at one boss. */
    @Query("select e from BossEncounter e where e.userId = :userId and e.bossId = :bossId "
            + "and e.cooldownUntil is not null and e.cooldownUntil > :now "
            + "order by e.cooldownUntil desc")
    List<BossEncounter> findCooldowns(@Param("userId") UUID userId,
                                      @Param("bossId") UUID bossId,
                                      @Param("now") Instant now);

    /**
     * Bosses this player has defeated, for the boss milestones.
     *
     * <p>Added by Phase 7. Filtered on {@code VICTORY} explicitly rather than
     * counting every encounter, so losses and expiries do not read as wins. A defeat
     * genuinely is not progress here, and counting it would reward players for
     * walking into a fight they cannot finish.
     */
    long countByUserIdAndStatus(UUID userId, EncounterStatus status);
}