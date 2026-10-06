package com.cyberheist.daily;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The materialised objective set for a date.
 *
 * <p>Rows are created once per date by the first player who asks, and then reused by
 * everyone. Because the set is frozen at creation, two players opening the daily
 * page minutes apart cannot be shown different objectives.
 */
public interface DailyChallengeRepository extends JpaRepository<DailyChallenge, UUID> {

    List<DailyChallenge> findByChallengeDateOrderByCodeAsc(LocalDate challengeDate);

    Optional<DailyChallenge> findByChallengeDateAndCode(LocalDate challengeDate, String code);

    long countByChallengeDate(LocalDate challengeDate);
}
