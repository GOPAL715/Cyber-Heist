package com.cyberheist.bonus;

import com.cyberheist.shop.EquipmentBonusService;
import com.cyberheist.shop.ItemEffectType;
import com.cyberheist.skill.SkillBonusService;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that answers "what is this player's loadout and skill tree
 * worth?".
 *
 * <pre>
 *   Player
 *     ├── EquipmentBonusService  ─┐
 *     └── SkillBonusService      ─┴─▶ PlayerBonusService ─▶ Missions / Energy
 * </pre>
 *
 * <p>Phase 4 had a single source of bonuses. Phase 5 adds a second, and this
 * class is what stops that turning into two competing answers. Anything that
 * modifies a reward or a cost reads from here; nothing adds a percentage to a
 * number on its own.
 *
 * <h2>Why the cap is applied here</h2>
 * Each source is summed raw and the <em>combined</em> total is then clamped. The
 * alternative - capping each source independently - would let equipment and skills
 * each reach 50% XP for a combined 100%, quietly doubling the ceiling Phase 4
 * balanced against. Summing first and capping once means the economy keeps the
 * ceiling it was designed around, and a player who maxes the skill tree still
 * cannot trivialise the game.
 *
 * <h2>Attribution</h2>
 * {@link #bonusesFor} returns the effective numbers. {@link #breakdownFor}
 * additionally reports what each source contributed before capping, which is what
 * the inventory and skill screens show so a player can see why a bonus stopped
 * increasing.
 */
@Service
public class PlayerBonusService {

    private final EquipmentBonusService equipmentBonusService;
    private final SkillBonusService skillBonusService;

    public PlayerBonusService(EquipmentBonusService equipmentBonusService,
                              SkillBonusService skillBonusService) {
        this.equipmentBonusService = equipmentBonusService;
        this.skillBonusService = skillBonusService;
    }

    /**
     * The player's effective bonuses: equipment plus skills, capped.
     *
     * <p>This is the only method a mechanic should call. It is deliberately a
     * single round trip for both sources so the two cannot be read at different
     * moments and disagree.
     *
     * <p>Only types with a non-zero capped total appear in the map.
     */
    @Transactional(readOnly = true)
    public Map<ItemEffectType, Integer> bonusesFor(UUID userId) {
        return cap(equipmentBonusService.bonusesFor(userId), skillBonusService.bonusesFor(userId));
    }

    /** The effective bonus of one type, or zero when nothing contributes. */
    @Transactional(readOnly = true)
    public int bonusFor(UUID userId, ItemEffectType type) {
        return bonusesFor(userId).getOrDefault(type, 0);
    }

    /**
     * Per-source attribution, before capping.
     *
     * <p>Presentation only: the numbers a player sees next to their gear and
     * their skills. Nothing applies these - {@link #bonusesFor} does - so a
     * player is never shown an "equipment total" that the engine is not using.
     */
    @Transactional(readOnly = true)
    public Breakdown breakdownFor(UUID userId) {
        return new Breakdown(equipmentBonusService.bonusesFor(userId), skillBonusService.bonusesFor(userId));
    }

    /** Sums two raw totals and clamps the result. Package-private for testing. */
    Map<ItemEffectType, Integer> cap(Map<ItemEffectType, Integer> equipment,
                                     Map<ItemEffectType, Integer> skills) {
        if (equipment.isEmpty() && skills.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ItemEffectType, Integer> combined = new EnumMap<>(ItemEffectType.class);
        for (ItemEffectType type : ItemEffectType.values()) {
            int total = equipment.getOrDefault(type, 0) + skills.getOrDefault(type, 0);
            int capped = EquipmentBonusService.capBonus(type, total);
            if (capped > 0) {
                combined.put(type, capped);
            }
        }
        return Collections.unmodifiableMap(combined);
    }

    /**
     * What each source contributed, uncapped.
     *
     * <p>Deliberately offers no "combined" accessor: producing the effective
     * total requires the cap, which lives in {@code PlayerBonusService}, and an
     * uncapped sum reachable from here would eventually be mistaken for the real
     * number and applied to a payout.
     *
     * @param equipment equipment totals
     * @param skills    skill totals
     */
    public record Breakdown(Map<ItemEffectType, Integer> equipment, Map<ItemEffectType, Integer> skills) {
    }
}