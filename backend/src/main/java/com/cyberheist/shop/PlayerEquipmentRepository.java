package com.cyberheist.shop;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerEquipmentRepository extends JpaRepository<PlayerEquipment, UUID> {
    List<PlayerEquipment> findByUserId(UUID userId);
    Optional<PlayerEquipment> findByUserIdAndSlot(UUID userId, EquipmentSlot slot);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PlayerEquipment e where e.userId = :userId and e.slot = :slot")
    Optional<PlayerEquipment> findByUserIdAndSlotForUpdate(@Param("userId") UUID userId,
                                                           @Param("slot") EquipmentSlot slot);
}
