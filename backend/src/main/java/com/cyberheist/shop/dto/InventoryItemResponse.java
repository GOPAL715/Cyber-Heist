package com.cyberheist.shop.dto;

import com.cyberheist.shop.EquipmentSlot;
import com.cyberheist.shop.ItemCategory;
import com.cyberheist.shop.ItemRarity;
import java.util.List;
import java.util.UUID;

public record InventoryItemResponse(
        UUID inventoryId,
        UUID itemId,
        String code,
        String name,
        String description,
        ItemCategory category,
        ItemRarity rarity,
        EquipmentSlot slot,
        int quantity,
        boolean equipped,
        EquipmentSlot equippedIn,
        List<ShopItemResponse.EffectView> effects
) {
}
