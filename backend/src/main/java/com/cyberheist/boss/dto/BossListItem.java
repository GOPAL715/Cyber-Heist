package com.cyberheist.boss.dto;

import java.util.List;
import java.util.UUID;

/**
 * One boss as the calling player sees it.
 *
 * <p>Everything is server-derived: the price of entry, the level gate, the
 * cooldown and the player's own state. There is no field a client could use to
 * describe an encounter instead of asking for one.
 *
 * @param canStart      the server's verdict, used directly for the button
 * @param blockedReason player-safe explanation when it cannot be started
 * @param activeEncounterId the live encounter, when there is one
 */
public record BossListItem(
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
        String availability,
        boolean canStart,
        String blockedReason,
        java.time.Instant cooldownUntil,
        UUID activeEncounterId,
        List<StageView> stages
) {

    /** A phase of this boss. Damage is the server's to apply, not the client's. */
    public record StageView(
            int stageNumber,
            String name,
            String description,
            String puzzleType,
            int damageValue
    ) {
        public static StageView of(com.cyberheist.boss.BossStage stage) {
            return new StageView(
                    stage.getStageNumber(),
                    stage.getName(),
                    stage.getDescription(),
                    stage.getPuzzleType().name(),
                    stage.getDamageValue());
        }
    }
}