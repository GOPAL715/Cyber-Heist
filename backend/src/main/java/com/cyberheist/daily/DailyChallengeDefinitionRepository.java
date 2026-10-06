package com.cyberheist.daily;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * The authored pool today's objectives are drawn from.
 *
 * <p>Ordered by {@code sortOrder} so the rotation is deterministic: the selection
 * shuffles a fixed sequence rather than an arbitrary one, which is what lets the
 * same date always produce the same set.
 */
public interface DailyChallengeDefinitionRepository extends JpaRepository<DailyChallengeDefinition, UUID> {

    List<DailyChallengeDefinition> findByActiveTrueOrderBySortOrderAsc();

    /**
     * Takes a write lock on the pool, in {@code sortOrder} order.
     *
     * <p>Used only while materialising a date for the first time. Two players opening
     * a fresh day at the same moment both find it missing; this lock makes them queue
     * so the second one re-reads and sees the rows the first one wrote.
     *
     * <p>The obvious alternative - let both attempt the insert and catch the unique
     * violation - cannot work inside a caller's transaction. A constraint failure
     * poisons the surrounding transaction, so the second player's mission completion
     * would roll back with it. Serialising before the write avoids that entirely, and
     * because every caller locks in this one order it cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DailyChallengeDefinition d where d.active = true order by d.sortOrder asc")
    List<DailyChallengeDefinition> findActiveForUpdate();
}
