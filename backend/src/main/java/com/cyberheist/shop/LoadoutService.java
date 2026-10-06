package com.cyberheist.shop;

import com.cyberheist.shop.dto.EquipmentListResponse;
import com.cyberheist.shop.dto.InventoryItemResponse;
import com.cyberheist.shop.dto.ShopItemResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the player's loadout.
 *
 * <p>Every slot is always returned, including empty ones, in a fixed order. The
 * inventory screen can then draw five panels without inventing placeholder rows
 * of its own, and two players with different loadouts still produce the same
 * shape.
 *
 * <p>The aggregated bonuses are read from {@link EquipmentBonusService} rather
 * than recomputed, so the numbers the loadout screen shows are by construction
 * the numbers the reward and energy paths apply.
 */
@Service
public class LoadoutService {

    private final PlayerInventoryRepository inventory;
    private final ItemRepository items;
    private final ItemEffectReader effectReader;
    private final PlayerEquipmentRepository equipment;
    private final EquipmentBonusService bonuses;

    public LoadoutService(PlayerInventoryRepository inventory,
                          ItemRepository items,
                          ItemEffectReader effectReader,
                          PlayerEquipmentRepository equipment,
                          EquipmentBonusService bonuses) {
        this.inventory = inventory;
        this.items = items;
        this.effectReader = effectReader;
        this.equipment = equipment;
        this.bonuses = bonuses;
    }

    @Transactional(readOnly = true)
    public EquipmentListResponse loadout(UUID userId) {
        List<PlayerEquipment> equippedRows = equipment.findByUserId(userId);

        Map<UUID, PlayerInventoryItem> ownedByInventoryId = new HashMap<>();
        inventory.findByUserId(userId).forEach(row -> ownedByInventoryId.put(row.getId(), row));

        // Resolve equipment -> inventory -> item before rendering, so a slot can
        // only ever show an item the player actually owns.
        Map<EquipmentSlot, PlayerEquipment> bySlot = new HashMap<>();
        Set<UUID> itemIds = new HashSet<>();
        for (PlayerEquipment row : equippedRows) {
            PlayerInventoryItem owned = ownedByInventoryId.get(row.getInventoryItemId());
            if (owned != null) {
                bySlot.put(row.getSlot(), row);
                itemIds.add(owned.getItemId());
            }
        }

        Map<UUID, Item> itemsById = new HashMap<>();
        if (!itemIds.isEmpty()) {
            items.findAllById(itemIds).forEach(item -> itemsById.put(item.getId(), item));
        }
        Map<UUID, List<ShopItemResponse.EffectView>> effects = effectReader.effectsFor(itemsById.keySet());

        List<EquipmentListResponse.SlotView> slots = new ArrayList<>(EquipmentSlot.values().length);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            PlayerEquipment row = bySlot.get(slot);
            PlayerInventoryItem owned = row == null ? null : ownedByInventoryId.get(row.getInventoryItemId());
            Item item = owned == null ? null : itemsById.get(owned.getItemId());

            InventoryItemResponse view = item == null ? null : new InventoryItemResponse(
                    owned.getId(),
                    item.getId(),
                    item.getCode(),
                    item.getName(),
                    item.getDescription(),
                    item.getCategory(),
                    item.getRarity(),
                    item.getEquipmentSlot(),
                    owned.getQuantity(),
                    true,
                    slot,
                    effects.getOrDefault(item.getId(), List.of()));

            slots.add(new EquipmentListResponse.SlotView(slot, view));
        }

        List<EquipmentListResponse.BonusView> bonusViews = bonuses.bonusesFor(userId).entrySet().stream()
                .map(entry -> new EquipmentListResponse.BonusView(entry.getKey().name(), entry.getValue()))
                .toList();

        return new EquipmentListResponse(slots, bonusViews);
    }
}