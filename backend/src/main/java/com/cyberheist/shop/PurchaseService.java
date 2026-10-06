package com.cyberheist.shop;

import com.cyberheist.exception.AlreadyOwnedException;
import com.cyberheist.exception.InsufficientCoinsException;
import com.cyberheist.exception.ItemNotFoundException;
import com.cyberheist.exception.ItemUnavailableException;
import com.cyberheist.game.PlayerMilestoneService;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.shop.dto.PurchaseResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying an item.
 *
 * <h2>The request means one thing</h2>
 * "Buy item X". The method takes an item id and nothing else. There is no price,
 * quantity, rarity or bonus parameter, so a body carrying
 * {@code {"price": 1, "coins": 1000000, "bonus": 999999}} has nowhere to bind:
 * Spring rejects the unknown fields as a malformed request rather than letting
 * one of them influence the outcome.
 *
 * <h2>Atomicity</h2>
 * The whole purchase is one transaction. The coin deduction and the inventory
 * insert either both commit or both roll back, so a player is never charged for
 * an item they did not receive, and never receives an item they did not pay for.
 *
 * <h2>Concurrency</h2>
 * The profile row is locked for update before the balance is read, so two
 * simultaneous purchases by the same player are serialised: the second sees the
 * balance the first already reduced. This is what makes it impossible for two
 * requests to spend the same coins twice and drive the balance negative, and it
 * also makes the duplicate-ownership check race-free without relying on catching
 * a constraint violation - which could not be recovered from inside this
 * transaction anyway.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final ItemRepository items;
    private final PlayerInventoryRepository inventory;
    private final PlayerProfileRepository profiles;
    private final PlayerMilestoneService milestoneService;

    public PurchaseService(ItemRepository items,
                           PlayerInventoryRepository inventory,
                           PlayerProfileRepository profiles,
                           PlayerMilestoneService milestoneService) {
        this.items = items;
        this.inventory = inventory;
        this.profiles = profiles;
        this.milestoneService = milestoneService;
    }

    /**
     * Charges the server-defined price and grants the item.
     *
     * <p>Order matters: every rejection happens before the first write, so a
     * refused purchase costs the player nothing and leaves no trace.
     *
     * @throws ItemNotFoundException      no such item
     * @throws ItemUnavailableException  the item has been retired
     * @throws AlreadyOwnedException     the player already owns this item; the
     *                                   balance is untouched
     * @throws InsufficientCoinsException the balance is short; nothing is deducted
     */
    @Transactional
    public PurchaseResponse purchase(UUID userId, UUID itemId) {
        Item item = items.findById(itemId).orElseThrow(ItemNotFoundException::new);
        if (!item.isActive()) {
            throw new ItemUnavailableException("This item is not available");
        }

        // Lock first, and read the balance from the locked row. The profile lock
        // serialises every purchase this player makes, so the affordability
        // check below and the deduction that follows cannot interleave with
        // another purchase's.
        PlayerProfile profile = profiles.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ItemNotFoundException("Player profile not found"));

        // One copy per player in Phase 4. Checked under the lock, so two
        // simultaneous attempts to buy the same item cannot both pass.
        if (inventory.findByUserIdAndItemId(userId, itemId).isPresent()) {
            throw new AlreadyOwnedException("You already own this item");
        }

        // Authoritative price: read from the catalogue row, never from a request.
        long price = item.getPrice();
        if (profile.getCoins() < price) {
            throw new InsufficientCoinsException(
                    "Not enough coins: this item costs " + price + ", you have " + profile.getCoins());
        }

        profile.spendCoins(price);
        profiles.save(profile);

        PlayerInventoryItem granted = inventory.save(
                new PlayerInventoryItem(UUID.randomUUID(), userId, itemId));

        log.info("Player {} bought {} for {} coins (balance now {})",
                userId, item.getCode(), price, profile.getCoins());

        // Phase 7. Reports that an item was bought; the inventory row is already
        // written above, so the collection milestones count this purchase rather than
        // needing to be told about it. Inside this transaction, so an unlock and its
        // payout commit with the purchase.
        milestoneService.itemPurchased(userId, profile);

        return new PurchaseResponse(
                granted.getId(),
                item.getId(),
                item.getCode(),
                item.getName(),
                price,
                profile.getCoins());
    }
}
