package com.cyberheist.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MissionRepository extends JpaRepository<Mission, UUID> {

    Optional<Mission> findByCode(String code);

    Optional<Mission> findByCodeAndActiveTrue(String code);

    List<Mission> findByActiveTrueOrderByRequiredLevelAscXpRewardAsc();

    List<Mission> findByActiveTrueAndCategoryOrderByRequiredLevelAscXpRewardAsc(MissionCategory category);
}