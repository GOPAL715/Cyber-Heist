package com.cyberheist.shop;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerInventoryRepository extends JpaRepository<PlayerInventoryItem, UUID> {
    List<PlayerInventoryItem> findByUserId(UUID userId);
    Optional<PlayerInventoryItem> findByIdAndUserId(UUID id, UUID userId);
    Optional<PlayerInventoryItem> findByUserIdAndItemId(UUID userId, UUID itemId);
    boolean existsByUserIdAndItemId(UUID userId, UUID itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PlayerInventoryItem i where i.userId = :userId and i.itemId = :itemId")
    Optional<PlayerInventoryItem> findByUserIdAndItemIdForUpdate(@Param("userId") UUID userId,
                                                                 @Param("itemId") UUID itemId);
}
