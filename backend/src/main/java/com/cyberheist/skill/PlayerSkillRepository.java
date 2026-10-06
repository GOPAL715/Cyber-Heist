package com.cyberheist.skill;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerSkillRepository extends JpaRepository<PlayerSkill, UUID> {

    List<PlayerSkill> findByUserId(UUID userId);

    Optional<PlayerSkill> findByUserIdAndSkillId(UUID userId, UUID skillId);

    /**
     * Loads a player's skill for update.
     *
     * <p>A pessimistic write lock so two concurrent unlocks of the same skill
     * serialise: without it both could read the same level and both write
     * level+1, charging two levels' cost for one level of progress. It is only
     * reached after the profile lock is held, so the two locks are always taken
     * profile first and cannot deadlock.
     *
     * <p>Note this only locks a row that already exists. The first-ever unlock of
     * a skill has no row to lock, which is why {@code SkillTreeService} also
     * takes the profile lock first: that is what serialises creation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ps from PlayerSkill ps where ps.userId = :userId and ps.skillId = :skillId")
    Optional<PlayerSkill> findByUserIdAndSkillIdForUpdate(@Param("userId") UUID userId,
                                                          @Param("skillId") UUID skillId);
}