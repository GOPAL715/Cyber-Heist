package com.cyberheist.skill;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillPrerequisiteRepository extends JpaRepository<SkillPrerequisite, SkillPrerequisiteId> {

    /** Edges whose gated skill is any of the given skills. */
    List<SkillPrerequisite> findByIdSkillIdIn(Collection<UUID> skillIds);
}