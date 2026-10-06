package com.cyberheist.achievement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The milestone catalogue.
 *
 * <p>Read-only in practice: nothing in the application creates or edits an
 * achievement, because the catalogue is seeded and tuned by migration. There is
 * deliberately no save path exposed beyond JPA's, so there is no endpoint that
 * could invent a milestone worth an arbitrary reward.
 */
public interface AchievementRepository extends JpaRepository<Achievement, UUID> {

    List<Achievement> findByActiveTrueOrderByCategoryAscSortOrderAsc();

    Optional<Achievement> findByCode(String code);

    Optional<Achievement> findByCodeAndActiveTrue(String code);
}
