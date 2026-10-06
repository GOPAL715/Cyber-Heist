package com.cyberheist.skill;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillLevelRepository extends JpaRepository<SkillLevel, UUID> {

    List<SkillLevel> findBySkillIdOrderByLevelAsc(UUID skillId);

    List<SkillLevel> findBySkillIdInOrderBySkillIdAscLevelAsc(Collection<UUID> skillIds);

    Optional<SkillLevel> findBySkillIdAndLevel(UUID skillId, int level);
}