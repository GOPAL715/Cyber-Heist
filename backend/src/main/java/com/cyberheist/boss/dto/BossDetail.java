package com.cyberheist.boss.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One boss in detail, with the caller's relationship to it.
 *
 * <p>Adds the phase list and the caller's history of this specific boss to what
 * the list endpoint returns. Every value is server-derived.
 *
 * @param timesDefeated how many of this boss's encounters this player has lost,
 *                      or null when they have never attempted it
 */
public record BossDetail(
        UUID id,
        String code,
        String name,
        String description,
        String difficulty,
        int requiredLevel,
        int energyCost,
        int stageCount,
        long xpReward,
        long coinReward,
        int cooldownVictoryMinutes,
        int cooldownDefeatMinutes,
        String availability,
        boolean canStart,
        String blockedReason,
        Instant cooldownUntil,
        List<BossListItem.StageView> stages,
        Integer timesDefeated
) {
}