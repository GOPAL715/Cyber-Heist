package com.cyberheist.shop;

import com.cyberheist.shop.dto.ShopItemResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@link ItemEffect} rows and groups them by the item they belong to.
 *
 * <p>Three services need effects attached to items - the shop, the inventory and
 * the loadout - and each was growing its own copy of the same grouping loop.
 * This component is the single implementation, so an effect cannot be rendered
 * one way in the shop and another way in the inventory.
 *
 * <p>Values come from the {@code item_effects} table and nowhere else. There is
 * no request path that can reach this class with a caller-supplied effect, which
 * is what makes an item's bonus server-authoritative.
 */
@Component
public class ItemEffectReader {

    private final ItemEffectRepository effectRepository;

    public ItemEffectReader(ItemEffectRepository effectRepository) {
        this.effectRepository = effectRepository;
    }

    /**
     * Effects for the given items, keyed by item id.
     *
     * <p>Items with no effects are simply absent from the map; callers decide
     * whether that means "no bonus" or "missing data".
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ShopItemResponse.EffectView>> effectsFor(Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ShopItemResponse.EffectView>> byItem = new HashMap<>();
        for (ItemEffect effect : effectRepository.findByItemIdIn(itemIds)) {
            byItem.computeIfAbsent(effect.getItemId(), key -> new ArrayList<>())
                    .add(new ShopItemResponse.EffectView(effect.getEffectType().name(), effect.getEffectValue()));
        }
        return byItem;
    }

    /** Effects for a single item; empty when it grants none. */
    @Transactional(readOnly = true)
    public List<ShopItemResponse.EffectView> effectsOf(UUID itemId) {
        List<ItemEffect> found = effectRepository.findByItemId(itemId);
        if (found.isEmpty()) {
            return List.of();
        }
        return found.stream()
                .map(effect -> new ShopItemResponse.EffectView(effect.getEffectType().name(), effect.getEffectValue()))
                .toList();
    }
}