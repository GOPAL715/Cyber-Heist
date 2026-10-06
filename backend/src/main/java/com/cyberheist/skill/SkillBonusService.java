package com.cyberheist.skill;

import com.cyberheist.shop.ItemEffectType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill half of the player's bonus total.
 *
 * <p>Reads the levels a player has actually unlocked and sums what those levels
 * grant. Deliberately returns <strong>raw, uncapped</strong> totals:
 * {@code PlayerBonusService} adds this to the equipment totals and caps the
 * combined result once, so the economy has a single ceiling and a single place
 * that enforces it.
 *
 * <p>Like {@code EquipmentBonusService}, every query is keyed on the
 * authenticated {@code userId}. There is no method that accepts a skill level, a
 * cost or an effect from a caller, which is why a request carrying
 * {@code "effectValue": 999999} has nowhere to bind.
 */
@Service
public class SkillBonusService {

    private final PlayerSkillRepository playerSkills;
    private final SkillLevelRepository skillLevels;

    public SkillBonusService(PlayerSkillRepository playerSkills, SkillLevelRepository skillLevels) {
        this.playerSkills = playerSkills;
        this.skillLevels = skillLevels;
    }

    /**
     * Uncapped bonus totals from the caller's unlocked skill levels.
     *
     * <p>A skill at level <em>n</em> contributes the value defined for level
     * <em>n</em>, not the sum of levels 1..n - each row is the total the level
     * grants, which keeps the balance readable in the table and makes the
     * aggregate a single lookup per skill rather than a prefix sum.
     *
     * <p>Only types with a non-zero total appear in the map.
     */
    @Transactional(readOnly = true)
    public Map<ItemEffectType, Integer> bonusesFor(UUID userId) {
        List<PlayerSkill> unlocked = playerSkills.findByUserId(userId);
        if (unlocked.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<ItemEffectType, Integer> totals = new EnumMap<>(ItemEffectType.class);
        for (PlayerSkill skill : unlocked) {
            skillLevels.findBySkillIdAndLevel(skill.getSkillId(), skill.getCurrentLevel())
                    .ifPresent(level -> totals.merge(level.getEffectType(),
                            level.getEffectValue(), Integer::sum));
        }
        totals.values().removeIf(total -> total <= 0);
        return Collections.unmodifiableMap(totals);
    }
}