package com.cyberheist.reward;

import com.cyberheist.player.PlayerProfile;
import com.cyberheist.progression.ProgressionResult;
import com.cyberheist.progression.ProgressionService;
import org.springframework.stereotype.Service;

/**
 * The single place where a player gains XP or coins.
 *
 * <p>This is the enforcement point for the central rule that the client never
 * determines rewards: the amounts always come from server-owned mission data,
 * and the only inputs are a {@link Reward} resolved from that data. There is no
 * API that accepts XP or coin amounts from a caller.
 *
 * <p>Kept separate from {@code MissionService} so other reward sources in later
 * phases (daily challenges, achievements, boss fights) reuse the same path.
 */
@Service
public class RewardService {

    private final ProgressionService progressionService;

    public RewardService(ProgressionService progressionService) {
        this.progressionService = progressionService;
    }

    /**
     * Applies a reward to the profile.
     *
     * <p>Does not persist: the caller's transaction owns that, so a failure
     * later in the mission flow rolls the payout back with everything else.
     *
     * @return the progression change, for the completion response
     */
    public ProgressionResult grant(PlayerProfile profile, Reward reward) {
        profile.addCoins(reward.coins());
        return progressionService.awardExperience(profile, reward.experience());
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