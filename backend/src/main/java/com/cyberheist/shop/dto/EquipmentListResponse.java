package com.cyberheist.shop.dto;

import com.cyberheist.shop.EquipmentSlot;
import java.util.List;

public record EquipmentListResponse(List<SlotView> equipment, List<BonusView> bonuses) {
    public record SlotView(EquipmentSlot slot, InventoryItemResponse item) {
    }

    public record BonusView(String type, int percent) {
    }
}
