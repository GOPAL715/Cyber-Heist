package com.cyberheist.shop;

import com.cyberheist.exception.ItemNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants the starter item to a newly created player.
 *
 * <h2>Why a starter item at all</h2>
 * Equipment bonuses only matter once something is equipped. Without a free
 * device a brand new player has an empty loadout, sees five empty slots and no
 * bonuses anywhere, and has no idea what the equipment system is for. The Basic
 * Laptop costs nothing and is granted here, so the first loadout screen is
 * populated on the very first visit.
 *
 * <h2>Why it is not chosen by the client</h2>
 * The item is looked up by a hard-coded code, so a registration request cannot
 * name it, choose it, or ask for a different one. There is no parameter through
 * which a starter selection could arrive.
 *
 * <h2>Why it is also equipped</h2>
 * Granted but not equipped would leave the inventory showing an owned item and a
 * loadout showing nothing, which reads as a bug. Equipping it costs no coins and
 * gives the player a small mission-speed bonus immediately.
 *
 * <p>Called from player creation inside the registration transaction, so a
 * failed grant rolls the account back rather than leaving a player with no
 * starter item.
 */
@Service
public class StarterEquipmentService {

    /** The only item a new player can ever receive for free. */
    public static final String STARTER_ITEM_CODE = "BASIC_LAPTOP";

    private final ItemRepository items;
    private final PlayerInventoryRepository inventory;
    private final PlayerEquipmentRepository equipment;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public StarterEquipmentService(ItemRepository items,
                                   PlayerInventoryRepository inventory,
                                   PlayerEquipmentRepository equipment) {
        this(items, inventory, equipment, Clock.systemUTC());
    }

    StarterEquipmentService(ItemRepository items,
                            PlayerInventoryRepository inventory,
                            PlayerEquipmentRepository equipment,
                            Clock clock) {
        this.items = items;
        this.inventory = inventory;
        this.equipment = equipment;
        this.clock = clock;
    }

    /**
     * Gives {@code userId} the starter device and equips it.
     *
     * <p>Idempotent: a player who already owns the starter item keeps their
     * existing inventory row, so calling this twice cannot create a duplicate or
     * fail the unique constraint.
     *
     * @return the inventory row holding the starter item
     */
    @Transactional
    public PlayerInventoryItem grantStarterItem(UUID userId) {
        Item starter = items.findByCode(STARTER_ITEM_CODE).orElseThrow(() ->
                new ItemNotFoundException("Starter item " + STARTER_ITEM_CODE + " is missing from the catalogue"));

        PlayerInventoryItem owned = inventory.findByUserIdAndItemId(userId, starter.getId()).orElse(null);
        if (owned == null) {
            owned = inventory.save(new PlayerInventoryItem(UUID.randomUUID(), userId, starter.getId()));
        }

        if (equipment.findByUserIdAndSlot(userId, starter.getEquipmentSlot()).isEmpty()) {
            equipment.save(new PlayerEquipment(
                    UUID.randomUUID(), userId, starter.getEquipmentSlot(), owned.getId(), clock.instant()));
        }
        return owned;
    }
}