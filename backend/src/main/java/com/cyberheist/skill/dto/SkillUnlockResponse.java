package com.cyberheist.skill.dto;

import java.util.List;
import java.util.UUID;

/**
 * The result of taking one level of a skill.
 *
 * <p>Reports the cost the <em>server</em> charged and the balance it left, so the
 * screen confirms the transaction rather than guessing at it. The request that
 * produced this carried only a skill id.
 *
 * @param cost     points actually spent, read from {@code skill_levels}
 * @param balance  points remaining afterwards
 * @param bonuses  the player's effective bonuses after the change
 */
public record SkillUnlockResponse(
        UUID skillId,
        String code,
        String name,
        int currentLevel,
        int maxLevel,
        int cost,
        String effectType,
        int effectValue,
        int balance,
        List<BonusView> bonuses
) {

    public record BonusView(String type, int percent) {
    }
}