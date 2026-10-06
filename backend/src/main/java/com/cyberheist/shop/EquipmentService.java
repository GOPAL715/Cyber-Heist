package com.cyberheist.shop;

import com.cyberheist.exception.EquipmentException;
import com.cyberheist.exception.ItemNotFoundException;
import com.cyberheist.exception.ItemUnavailableException;
import com.cyberheist.game.PlayerMilestoneService;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.shop.dto.InventoryItemResponse;
import com.cyberheist.shop.dto.InventoryListResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipping and unequipping.
 *
 * <h2>Serialisation</h2>
 * Every mutating path takes the player's profile row as a pessimistic write
 * lock <em>first</em>, before reading or writing any loadout row. That single
 * lock serialises all equipment changes for one player, so two concurrent
 * requests cannot both find the slot empty and both insert.
 *
 * <p>It also matches the ordering used by the mission flow - profile before
 * everything else - so no request can hold a loadout lock while waiting for the
 * profile, which is what would allow a deadlock between the two subsystems.
 *
 * <h2>Why not catch the unique violation instead</h2>
 * The alternative is to attempt the insert and translate the constraint
 * violation into a 409. That does not work in a single transaction: the failing
 * statement marks it rollback-only, so the retry that would follow cannot commit
 * either. Locking up front makes the conflict impossible rather than recoverable.
 */
@Service
public class EquipmentService {

    private final PlayerInventoryRepository inventory;
    private final ItemRepository items;
    private final PlayerEquipmentRepository equipment;
    private final InventoryService inventoryService;
    private final PlayerProfileRepository profiles;
    private final PlayerMilestoneService milestoneService;
    private final Clock clock;

    @Autowired
    public EquipmentService(PlayerInventoryRepository inventory,
                            ItemRepository items,
                            PlayerEquipmentRepository equipment,
                            InventoryService inventoryService,
                            PlayerProfileRepository profiles,
                            PlayerMilestoneService milestoneService) {
        this(inventory, items, equipment, inventoryService, profiles, milestoneService,
                Clock.systemUTC());
    }

    EquipmentService(PlayerInventoryRepository inventory,
                     ItemRepository items,
                     PlayerEquipmentRepository equipment,
                     InventoryService inventoryService,
                     PlayerProfileRepository profiles,
                     PlayerMilestoneService milestoneService,
                     Clock clock) {
        this.inventory = inventory;
        this.items = items;
        this.equipment = equipment;
        this.inventoryService = inventoryService;
        this.profiles = profiles;
        this.milestoneService = milestoneService;
        this.clock = clock;
    }

    /**
     * Equips an owned item into a slot, replacing whatever was there.
     *
     * <p>Checks run in the order that costs the least to get wrong: lock, then
     * ownership, then slot compatibility. Each failure is rejected before any
     * write, so a rejected equip leaves the previous loadout untouched.
     *
     * @throws ItemNotFoundException if the inventory row does not exist or
     *                               belongs to another player. Both are reported
     *                               identically, so a prober cannot use the error
     *                               to confirm that an id they guessed is real.
     * @throws EquipmentException   if the item does not belong in this slot
     * @throws ItemUnavailableException if the item has been retired
     */
    @Transactional
    public InventoryItemResponse equip(UUID userId, EquipmentSlot slot, UUID inventoryItemId) {
        // Lock 1: the profile. Serialises every loadout change for this player.
        PlayerProfile profile = profiles.findByUserIdForUpdate(userId).orElseThrow(() ->
                new ItemNotFoundException("Player profile not found"));

        // Locked by id *and* owner, so another player's inventory row is simply
        // not found here.
        PlayerInventoryItem owned = inventoryService.requireOwned(userId, inventoryItemId);

        Item item = items.findById(owned.getItemId()).orElseThrow(ItemNotFoundException::new);
        if (!item.isActive()) {
            throw new ItemUnavailableException("This item is not available");
        }
        if (item.getEquipmentSlot() != slot) {
            throw new EquipmentException(
                    "This item belongs in the " + item.getEquipmentSlot() + " slot, not " + slot);
        }

        Instant now = clock.instant();
        PlayerEquipment existing = equipment.findByUserIdAndSlot(userId, slot).orElse(null);
        if (existing == null) {
            equipment.save(new PlayerEquipment(
                    UUID.randomUUID(), userId, slot, owned.getId(), now));
        } else {
            // Reuse the row so equipped_at reflects the swap rather than growing
            // an unbounded history of replacements.
            existing.reequip(owned.getId(), now);
            equipment.save(existing);
        }

        // Phase 7. Reports that the loadout changed, after the row is written so the
        // equipment milestones measure the real slot count. Unequip reports too,
        // because FULLY_LOADED depends on the current total and a player who empties
        // a slot should see the milestones reflect that.
        milestoneService.equipmentChanged(userId, profile);

        return viewOf(userId, owned.getId());
    }

    /**
     * Empties a slot.
     *
     * <p>Deletes only the loadout row. The item stays in the inventory and can be
     * equipped again at any time, and any bonus it granted disappears because
     * aggregation reads the loadout rather than the inventory.
     *
     * <p>Idempotent for a slot that is already empty, so a double-click cannot
     * produce an error the player cannot act on.
     */
    @Transactional
    public void unequip(UUID userId, EquipmentSlot slot) {
        PlayerProfile profile = profiles.findByUserIdForUpdate(userId).orElseThrow(() ->
                new ItemNotFoundException("Player profile not found"));

        equipment.findByUserIdAndSlot(userId, slot).ifPresent(equipment::delete);

        // Phase 7, same reasoning as equip.
        milestoneService.equipmentChanged(userId, profile);
    }

    /** Renders one owned item through the inventory projection. */
    private InventoryItemResponse viewOf(UUID userId, UUID inventoryItemId) {
        InventoryListResponse all = inventoryService.inventory(userId);
        return all.items().stream()
                .filter(item -> item.inventoryId().equals(inventoryItemId))
                .findFirst()
                .orElseThrow(EquipmentException::notOwned);
    }
}