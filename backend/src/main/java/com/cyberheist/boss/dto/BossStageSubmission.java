package com.cyberheist.boss.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A boss stage submission.
 *
 * <p>Deliberately two fields and no more. There is no damage, integrity, stage,
 * reward, XP, coin or energy field here, because a request DTO that can express
 * them is a request DTO somebody eventually will.
 */
public record BossStageSubmission(
        @NotNull(message = "puzzleId is required") UUID puzzleId,
        @NotNull(message = "answer is required") String answer
) {
}