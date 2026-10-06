package com.cyberheist.shop;

import com.cyberheist.exception.ItemNotFoundException;
import com.cyberheist.exception.ItemUnavailableException;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.shop.dto.ShopItemResponse;
import com.cyberheist.shop.dto.ShopListResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the shop: the catalogue and item details.
 *
 * <p>Purchasing lives in {@link PurchaseService}. Splitting them keeps this
 * class a pure read - it never takes a lock and never writes - so browsing the
 * shop cannot contend with a purchase in progress.
 *
 * <p>The balance is returned alongside the catalogue because the shop screen has
 * to show it, and a second round trip purely to read coins would let the two
 * figures disagree on screen. It is read from the profile, never from the
 * request.
 */
@Service
public class ShopService {

    private final ItemRepository items;
    private final ItemEffectReader effectReader;
    private final PlayerInventoryRepository inventory;
    private final PlayerProfileRepository profiles;

    public ShopService(ItemRepository items,
                       ItemEffectReader effectReader,
                       PlayerInventoryRepository inventory,
                       PlayerProfileRepository profiles) {
        this.items = items;
        this.effectReader = effectReader;
        this.inventory = inventory;
        this.profiles = profiles;
    }

    /**
     * The active catalogue, cheapest first, each item marked with whether the
     * caller already owns it.
     *
     * <p>Inactive items are excluded by the query rather than filtered afterwards,
     * so a retired item cannot leak through a display filter or an ordering bug.
     */
    @Transactional(readOnly = true)
    public ShopListResponse catalogue(UUID userId) {
        List<Item> active = items.findByActiveTrueOrderByPriceAsc();
        Set<UUID> owned = inventory.findByUserId(userId).stream()
                .map(PlayerInventoryItem::getItemId)
                .collect(Collectors.toSet());
        Map<UUID, List<ShopItemResponse.EffectView>> effects = effectReader.effectsFor(
                active.stream().map(Item::getId).toList());

        List<ShopItemResponse> views = active.stream()
                .map(item -> view(item, effects.getOrDefault(item.getId(), List.of()), owned.contains(item.getId())))
                .toList();

        long coins = profiles.findByUserId(userId).map(PlayerProfile::getCoins).orElse(0L);
        return new ShopListResponse(views, coins);
    }

    /**
     * One item's server-defined definition.
     *
     * <p>An inactive item is reported as unavailable rather than as missing, which
     * is the honest answer: it exists, the player simply cannot buy it now.
     */
    @Transactional(readOnly = true)
    public ShopItemResponse detail(UUID userId, UUID itemId) {
        Item item = items.findById(itemId).orElseThrow(ItemNotFoundException::new);
        if (!item.isActive()) {
            throw new ItemUnavailableException("This item is not available");
        }
        boolean owned = inventory.findByUserIdAndItemId(userId, itemId).isPresent();
        return view(item, effectReader.effectsOf(itemId), owned);
    }

    private ShopItemResponse view(Item item, List<ShopItemResponse.EffectView> effects, boolean owned) {
        return new ShopItemResponse(
                item.getId(),
                item.getCode(),
                item.getName(),
                item.getDescription(),
                item.getCategory(),
                item.getRarity(),
                item.getEquipmentSlot(),
                item.getPrice(),
                owned,
                effects);
    }
}