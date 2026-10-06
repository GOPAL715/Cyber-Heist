package com.cyberheist.shop;

import com.cyberheist.exception.ItemNotFoundException;
import com.cyberheist.shop.dto.InventoryItemResponse;
import com.cyberheist.shop.dto.InventoryListResponse;
import com.cyberheist.shop.dto.ShopItemResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the player's own inventory.
 *
 * <p>Everything here is keyed on the {@code userId} the controller resolved from
 * the security context. There is no method that accepts a user id from a
 * request, so there is no way to ask this class for another player's inventory.
 *
 * <p>Ownership and equipped state are joined in memory from three small queries
 * rather than in JPQL because the catalogue is fifteen rows and fits without
 * touching; the queries are all indexed on {@code user_id}.
 */
@Service
public class InventoryService {

    private final PlayerInventoryRepository inventoryRepository;
    private final ItemRepository itemRepository;
    private final ItemEffectReader effectReader;
    private final PlayerEquipmentRepository equipmentRepository;

    public InventoryService(PlayerInventoryRepository inventoryRepository,
                            ItemRepository itemRepository,
                            ItemEffectReader effectReader,
                            PlayerEquipmentRepository equipmentRepository) {
        this.inventoryRepository = inventoryRepository;
        this.itemRepository = itemRepository;
        this.effectReader = effectReader;
        this.equipmentRepository = equipmentRepository;
    }

    /**
     * Everything the caller owns, each with its rarity, effects and whether it
     * is currently equipped.
     *
     * <p>The equipped flag is derived from the loadout rather than stored on the
     * inventory row, so the two can never disagree.
     */
    @Transactional(readOnly = true)
    public InventoryListResponse inventory(UUID userId) {
        List<PlayerInventoryItem> rows = inventoryRepository.findByUserId(userId);
        if (rows.isEmpty()) {
            return new InventoryListResponse(List.of());
        }

        Map<UUID, Item> itemsById = new HashMap<>();
        itemRepository.findAllById(rows.stream().map(PlayerInventoryItem::getItemId).toList())
                .forEach(item -> itemsById.put(item.getId(), item));

        Map<UUID, List<ShopItemResponse.EffectView>> effects = effectReader.effectsFor(itemsById.keySet());

        Map<UUID, EquipmentSlot> equippedSlotByInventoryId = new HashMap<>();
        equipmentRepository.findByUserId(userId)
                .forEach(equipment -> equippedSlotByInventoryId.put(equipment.getInventoryItemId(), equipment.getSlot()));

        List<InventoryItemResponse> views = new ArrayList<>(rows.size());
        for (PlayerInventoryItem row : rows) {
            Item item = itemsById.get(row.getItemId());
            if (item == null) {
                // An item deleted from the catalogue while owned. The foreign
                // key is RESTRICT so this should be unreachable; skipping is
                // preferable to failing the whole inventory request.
                continue;
            }
            EquipmentSlot equippedIn = equippedSlotByInventoryId.get(row.getId());
            views.add(new InventoryItemResponse(
                    row.getId(),
                    item.getId(),
                    item.getCode(),
                    item.getName(),
                    item.getDescription(),
                    item.getCategory(),
                    item.getRarity(),
                    item.getEquipmentSlot(),
                    row.getQuantity(),
                    equippedIn != null,
                    equippedIn,
                    effects.getOrDefault(item.getId(), List.of())));
        }
        return new InventoryListResponse(views);
    }

    /**
     * One owned item, confirmed to belong to {@code userId}.
     *
     * <p>Used by the equip path, where ownership has to be proven before
     * anything is written.
     */
    @Transactional(readOnly = true)
    public PlayerInventoryItem requireOwned(UUID userId, UUID inventoryItemId) {
        return inventoryRepository.findByIdAndUserId(inventoryItemId, userId)
                .orElseThrow(ItemNotFoundException::new);
    }
}