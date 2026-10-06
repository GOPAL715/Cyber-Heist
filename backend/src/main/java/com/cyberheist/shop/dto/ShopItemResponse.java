package com.cyberheist.shop.dto;

import com.cyberheist.shop.EquipmentSlot;
import com.cyberheist.shop.ItemCategory;
import com.cyberheist.shop.ItemRarity;
import java.util.List;
import java.util.UUID;

public record ShopItemResponse(
        UUID id,
        String code,
        String name,
        String description,
        ItemCategory category,
        ItemRarity rarity,
        EquipmentSlot slot,
        long price,
        boolean owned,
        List<EffectView> effects
) {
    public record EffectView(String type, int value) {
    }
}
