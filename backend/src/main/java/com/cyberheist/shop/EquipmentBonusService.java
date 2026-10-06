package com.cyberheist.shop;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place equipped-item bonuses are calculated.
 *
 * <h2>Why this class exists</h2>
 * Every gameplay number that equipment is allowed to influence - XP, coins,
 * energy cost, puzzle difficulty, mission speed - is derived here and read by
 * whichever service owns that mechanic. No other class may add a percentage to
 * a reward, so there is exactly one definition of "what is this player worth
 * right now" and it cannot drift between the dashboard and the mission flow.
 *
 * <h2>Authority</h2>
 * The percentages come from {@code item_effects}, which is seeded by migration
 * and never written by an endpoint. There is deliberately no method that
 * accepts an effect from a caller, which is why a request carrying
 * {@code "bonus": 999999} has nowhere to land: it does not parse into any
 * argument of any method here.
 *
 * <h2>Caps</h2>
 * Totals are clamped per effect type by {@code PlayerBonusService} after
 * equipment and skills have been added together. The ceilings themselves are
 * declared here, because they arrived with Phase 4 and are part of its
 * contract: a loadout plus a skill tree cannot stack without limit.
 *
 * <h2>Rounding</h2>
 * All arithmetic is integer. {@code base + round(base * percent / 100)} is
 * evaluated as {@code base + (base * percent + 50) / 100}, which rounds halves
 * up using {@code long} throughout. Floating point is avoided deliberately:
 * {@code 50 * 0.10} is not exactly {@code 5} in binary floating point, and a
 * reward that varied with the representation of the decimal would be a
 * rounding bug waiting to be reported as a wrong payout.
 */
@Service
public class EquipmentBonusService {

    /**
     * Maximum aggregate bonus per effect type, as a percentage.
     *
     * <p>These bound the economy. XP and coins cap at 50% because a loadout
     * that more than doubled earnings would make mission rewards irrelevant; the
     * remaining three cap at 30% so no single slot can trivially trivialise the
     * mechanics it touches.
     */
    public static final int MAX_EXPERIENCE_BONUS = 50;
    public static final int MAX_COIN_BONUS = 50;
    public static final int MAX_ENERGY_EFFICIENCY = 30;
    public static final int MAX_MISSION_SPEED = 30;
    public static final int MAX_PUZZLE_BONUS = 30;

    /** Energy never costs less than this, whatever the discount. */
    private static final int MINIMUM_ENERGY_COST = 1;

    private final PlayerEquipmentRepository equipmentRepository;
    private final PlayerInventoryRepository inventoryRepository;
    private final ItemEffectRepository effectRepository;

    public EquipmentBonusService(PlayerEquipmentRepository equipmentRepository,
                                 PlayerInventoryRepository inventoryRepository,
                                 ItemEffectRepository effectRepository) {
        this.equipmentRepository = equipmentRepository;
        this.inventoryRepository = inventoryRepository;
        this.effectRepository = effectRepository;
    }

    /**
     * Aggregate bonuses for a player's current loadout.
     *
     * <p><strong>Returns raw, uncapped totals.</strong> Since Phase 5 a player's
     * bonuses are the sum of equipment and skills, and the cap is applied once to
     * that combined total by {@code PlayerBonusService}. Capping here as well
     * would be harmless numerically but would misrepresent the split, and it would
     * put the economy's ceiling in two places.
     *
     * <p>Only types with a non-zero total appear in the map, so a caller can
     * treat absence as "no bonus" without knowing every enum constant.
     *
     * <p>Reads are scoped to the caller's own equipment and inventory rows, so
     * the answer is never influenced by another player's items even when two
     * players' loadouts are aggregated in the same request.
     */
    @Transactional(readOnly = true)
    public Map<ItemEffectType, Integer> bonusesFor(UUID userId) {
        List<PlayerEquipment> equipped = equipmentRepository.findByUserId(userId);
        if (equipped.isEmpty()) {
            return Collections.emptyMap();
        }

        // Equipment references inventory rows, not items, so the two-step join
        // is what ties a bonus back to the definition that grants it.
        Map<UUID, PlayerInventoryItem> owned = inventoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(PlayerInventoryItem::getId, item -> item));

        List<UUID> itemIds = equipped.stream()
                .map(equipment -> owned.get(equipment.getInventoryItemId()))
                .filter(Objects::nonNull)
                .map(PlayerInventoryItem::getItemId)
                .toList();
        if (itemIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<ItemEffectType, Integer> totals = new EnumMap<>(ItemEffectType.class);
        for (ItemEffect effect : effectRepository.findByItemIdIn(itemIds)) {
            totals.merge(effect.getEffectType(), effect.getEffectValue(), Integer::sum);
        }
        totals.values().removeIf(total -> total <= 0);
        return Collections.unmodifiableMap(totals);
    }

/**
     * Clamps a raw total to the ceiling for its effect type.
 *
     * <p>Since Phase 5 this is the <em>global</em> ceiling, applied by
     * {@code PlayerBonusService} to equipment and skills together. The
     * constants live here because they arrived with Phase 4 and are part of its
     * contract.
     */
    public int cappedBonus(ItemEffectType type, int rawTotal) {
        return capBonus(type, rawTotal);
    }

    /**
     * The clamping rule itself, as static arithmetic.
     *
     * <p>Static so {@code PlayerBonusService} can apply the ceiling without
     * holding a reference to this service purely to reach it, and so the rule
     * can be exercised without constructing a Spring context.
     */
    public static int capBonus(ItemEffectType type, int raw) {
        int ceiling = switch (type) {
            case EXPERIENCE_BONUS -> MAX_EXPERIENCE_BONUS;
            case COIN_BONUS -> MAX_COIN_BONUS;
            case ENERGY_EFFICIENCY -> MAX_ENERGY_EFFICIENCY;
            case MISSION_SPEED -> MAX_MISSION_SPEED;
            case PUZZLE_BONUS -> MAX_PUZZLE_BONUS;
        };
        return Math.max(0, Math.min(raw, ceiling));
    }

    /**
     * Applies a percentage bonus to a base amount, rounding halves up.
     *
     * <pre>
     * final = base + (base * percent + 50) / 100
     * </pre>
     *
     * <p>Used for XP and coins. {@code 50} XP at {@code +10%} becomes
     * {@code 55}; {@code 28} coins at {@code +15%} becomes {@code 32}.
     */
    public static long applyPercentBonus(long base, int percent) {
        if (base <= 0 || percent <= 0) {
            return Math.max(0, base);
        }
        return base + (base * percent + 50) / 100;
    }

    /**
     * Applies an energy discount, rounding halves up and never reaching zero.
     *
     * <pre>
     * cost = (base * (100 - percent) + 50) / 100, floored at 1
     * </pre>
     *
     * <p>A mission that costs 20 energy at {@code +10%} efficiency costs 18.
     * The floor at 1 means efficiency can make a mission cheaper but never
     * free, so it cannot be used to bypass a mission's minimum cost.
     */
    public static int applyEnergyDiscount(int baseCost, int percent) {
        if (baseCost <= 0) {
            return Math.max(0, baseCost);
        }
        int effective = Math.min(Math.max(percent, 0), MAX_ENERGY_EFFICIENCY);
        long discounted = ((long) baseCost * (100 - effective) + 50) / 100;
        return (int) Math.max(MINIMUM_ENERGY_COST, discounted);
    }
}