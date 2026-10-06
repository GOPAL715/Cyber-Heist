package com.cyberheist.boss;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BossStageRepository extends JpaRepository<BossStage, UUID> {

    List<BossStage> findByBossIdOrderByStageNumberAsc(UUID bossId);

    List<BossStage> findByBossIdInOrderByBossIdAscStageNumberAsc(Collection<UUID> bossIds);

    Optional<BossStage> findByBossIdAndStageNumber(UUID bossId, int stageNumber);
}