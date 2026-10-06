package com.cyberheist.boss;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BossRepository extends JpaRepository<Boss, UUID> {
    List<Boss> findByActiveTrueOrderByRequiredLevelAscCodeAsc();
    Optional<Boss> findByCode(String code);
}