package com.cyberheist.daily;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * Per-day event totals.
 *
 * <p>The locking finders matter for correctness under concurrency: two mission
 * completions arriving together for the same player and metric must add to one row,
 * not create two. The unique constraint would stop the duplicate row, but the lock is
 * what stops the lost update, so both are present.
 */
public interface PlayerDailyCounterRepository extends JpaRepository<PlayerDailyCounter, UUID> {

    List<PlayerDailyCounter> findByUserIdAndBusinessDate(UUID userId, LocalDate businessDate);

    Optional<PlayerDailyCounter> findByUserIdAndBusinessDateAndMetric(UUID userId,
                                                                     LocalDate businessDate,
                                                                     DailyMetric metric);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PlayerDailyCounter c where c.userId = :userId "
            + "and c.businessDate = :date and c.metric = :metric")
    Optional<PlayerDailyCounter> findForUpdate(@Param("userId") UUID userId,
                                               @Param("date") LocalDate date,
                                               @Param("metric") DailyMetric metric);
}
