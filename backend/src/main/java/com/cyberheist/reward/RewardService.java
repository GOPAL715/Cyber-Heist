package com.cyberheist.reward;

import com.cyberheist.player.PlayerProfile;
import com.cyberheist.progression.ProgressionResult;
import com.cyberheist.progression.ProgressionService;
import com.cyberheist.shop.EquipmentBonusService;
import com.cyberheist.shop.ItemEffectType;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The single place where a player gains XP or coins.
 *
 * <p>This is the enforcement point for the central rule that the client never
 * determines rewards: the base amounts always come from server-owned mission
 * data, and the only caller-supplied values anywhere on the path are a user id
 * resolved from the security context. There is no API that accepts XP or coin
 * amounts from a caller.
 *
 * <p>Kept separate from {@code MissionService} so other reward sources in later
 * phases (daily challenges, achievements, boss fights) reuse the same path.
 *
 * <h2>Equipment bonuses</h2>
 * Phase 4 modifiers are applied through {@link #applyBonuses} rather than by
 * each reward source, so the rule "base reward, then equipment, then final" is
 * implemented once. A future source of XP gets equipment bonuses by following
 * the same call, and no source can accidentally skip them. The bonuses must
 * already be capped; {@link EquipmentBonusService} is what does that.
 */
@Service
public class RewardService {

    private final ProgressionService progressionService;

    public RewardService(ProgressionService progressionService) {
        this.progressionService = progressionService;
    }

    /**
     * Applies a reward to the profile, with no equipment modifier.
     *
     * <p>Retained for callers that have already decided the final amounts, and
     * for the many Phase 2 and 3 tests that assert exact payouts.
     */
    public ProgressionResult grant(PlayerProfile profile, Reward reward) {
        profile.addCoins(reward.coins());
        return progressionService.awardExperience(profile, reward.experience());
    }

    /**
     * The reward-modification rule, isolated so it can be reasoned about and
     * tested without a database.
     *
     * <pre>
     * final = base + (base * bonus + 50) / 100
     * </pre>
     *
     * <p>Integer arithmetic with halves rounded up, so a given base and bonus
     * always produce the same result. See {@link EquipmentBonusService} for why
     * floating point is avoided.
     */
    public static Reward applyBonuses(Reward base, Map<ItemEffectType, Integer> bonuses) {
        long experience = EquipmentBonusService.applyPercentBonus(
                base.experience(), bonuses.getOrDefault(ItemEffectType.EXPERIENCE_BONUS, 0));
        long coins = EquipmentBonusService.applyPercentBonus(
                base.coins(), bonuses.getOrDefault(ItemEffectType.COIN_BONUS, 0));
        return new Reward(experience, coins);
    }

    /**
     * Reads the player's current progression without changing anything.
     *
     * <p>Used when reporting an idempotent replayed completion, where the payout
     * is zero but the caller still expects accurate level and XP figures.
     */
    public ProgressionResult currentProgression(PlayerProfile profile) {
        return progressionService.describe(profile);
    }
}