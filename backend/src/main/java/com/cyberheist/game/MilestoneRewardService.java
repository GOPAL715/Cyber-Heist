package com.cyberheist.game;

import com.cyberheist.bonus.PlayerBonusService;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.progression.ProgressionResult;
import com.cyberheist.reward.Reward;
import com.cyberheist.reward.RewardService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The one path a Phase 7 reward travels.
 *
 * <p>Achievement rewards, daily challenge rewards and streak milestone rewards all
 * come through here, and this method does exactly what a mission reward does: apply
 * the player's capped bonuses, then hand the result to {@link RewardService}. There
 * is no second reward engine and no second multiplier table.
 *
 * <p>That reuse is what keeps the existing invariants true for Phase 7 rather than
 * merely resembling them. An achievement that grants XP still goes through the level
 * curve, still grants a skill point per level crossed, and still respects a 50%
 * experience bonus from a equipped item - because it calls the same two methods
 * {@code MissionService} calls.
 *
 * <p>It is also the single reason Phase 7 needed no new reward column: the payout is
 * an ordinary grant, so lifetime coins increment through
 * {@code PlayerProfile.addCoins} like everything else.
 */
@Service
public class MilestoneRewardService {

    private final RewardService rewards;
    private final PlayerBonusService bonuses;

    public MilestoneRewardService(RewardService rewards, PlayerBonusService bonuses) {
        this.rewards = rewards;
        this.bonuses = bonuses;
    }

    /**
     * Pays a milestone reward, with the player's bonuses applied.
     *
     * <p>Callers pass the catalogue base amount. Nothing here is reachable with a
     * caller-supplied figure: the {@code Reward} handed in always comes from a
     * server-owned row.
     *
     * @param profile  the already-locked profile to credit
     * @param base     the catalogue reward, before bonuses
     * @return the final amounts paid alongside the resulting progression, so a
     *         notification can state what was actually credited rather than what
     *         the catalogue promised
     */
    public Payout pay(PlayerProfile profile, Reward base) {
        Reward finalReward = RewardService.applyBonuses(base, bonuses.bonusesFor(profile.getUserId()));
        ProgressionResult progression = rewards.grant(profile, finalReward);
        return new Payout(finalReward, progression);
    }

    /**
     * The final post-bonus amount for a reward, without paying it.
     *
     * <p>Used where a caller needs to state what a milestone <em>would</em> pay -
     * the achievement gallery, for instance - so the previewed figure is the real one
     * rather than the un-bonused base.
     */
    public Reward preview(UUID userId, Reward base) {
        return RewardService.applyBonuses(base, bonuses.bonusesFor(userId));
    }

    /**
     * What a milestone actually paid, and what it did to the player.
     *
     * @param finalReward the post-bonus amounts credited
     * @param progression the resulting level and XP state
     */
    public record Payout(Reward finalReward, ProgressionResult progression) {
        public long experience() {
            return finalReward.experience();
        }

        public long coins() {
            return finalReward.coins();
        }

        public int levelsGained() {
            return progression.levelsGained();
        }
    }
}
