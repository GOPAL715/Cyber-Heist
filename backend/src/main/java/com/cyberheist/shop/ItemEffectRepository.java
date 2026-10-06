package com.cyberheist.shop;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemEffectRepository extends JpaRepository<ItemEffect, UUID> {
    List<ItemEffect> findByItemId(UUID itemId);
    List<ItemEffect> findByItemIdIn(java.util.Collection<UUID> itemIds);
}
