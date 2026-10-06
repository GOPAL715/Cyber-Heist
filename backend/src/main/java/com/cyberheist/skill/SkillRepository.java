package com.cyberheist.skill;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Skill, UUID> {
    List<Skill> findByActiveTrueOrderByBranchAscNameAsc();

    Optional<Skill> findByCode(String code);
}