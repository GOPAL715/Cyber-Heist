package com.cyberheist.game;

import com.cyberheist.achievement.AchievementService;
import com.cyberheist.daily.DailyChallengeService;
import com.cyberheist.daily.DailyMetric;
import com.cyberheist.daily.StreakService;
import com.cyberheist.player.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single door between Phase 1-6 and Phase 7.
 *
 * <p>Every existing service that performs a meaningful action calls into this class
 * and nothing else. None of them knows that achievements or daily challenges exist:
 * a mission completion reports that it happened, and this class decides what that
 * means for milestones. That is the whole integration surface - four call sites in
 * four services - and it is why adding a new milestone type later does not mean
 * editing a mission, a boss, a purchase and a skill unlock.
 *
 * <h2>Ordering, and why it is fixed</h2>
 * <ol>
 *   <li><b>Daily counters first.</b> They are the raw facts. An achievement that
 *       counts something, and a daily objective that counts the same thing, must both
 *       see the event before either is evaluated.</li>
 *   <li><b>Streak.</b> Cheap, and it may pay a milestone that raises the level.</li>
 *   <li><b>Daily evaluation.</b> May complete objectives and pay.</li>
 *   <li><b>Achievements last.</b> Achievements read derived state, including the
 *       level and lifetime coins the two steps above may just have changed.</li>
 * </ol>
 *
 * <p>Doing it in this order means one evaluation sees the fully updated player, so a
 * mission that levels the player up can unlock {@code RISING_HACKER} on the same
 * request instead of the next one.
 *
 * <h2>Transactions</h2>
 * Every method here runs in the caller's transaction, or opens one when called from a
 * read. Two consequences worth naming: an unlock and its reward always commit
 * together with the event that caused it, and a failure anywhere rolls all of it
 * back together rather than leaving an achievement marked earned with no payout.
 */
@Service
public class PlayerMilestoneService {

    private static final Logger log = LoggerFactory.getLogger(PlayerMilestoneService.class);

    /**
     * How many times one event may trigger a fresh achievement pass.
     *
     * <p>Paying an achievement grants XP, which can cross a level boundary, which can
     * satisfy {@code RISING_HACKER}, which pays XP again. One pass would leave that
     * achievement for the player's next action, so a mission that levels them up would
     * not credit the level milestone until they did something else.
     *
     * <p>Each further pass has to unlock something genuinely new to continue, so this
     * terminates on its own. The bound is a backstop for a pathological catalogue: it
     * degrades to "the rest arrive next time" rather than looping.
     */
    private static final int MAX_ACHIEVEMENT_PASSES = 3;

    private final AchievementService achievements;
    private final DailyChallengeService dailyChallenges;
    private final StreakService streaks;

    public PlayerMilestoneService(AchievementService achievements,
                                  DailyChallengeService dailyChallenges,
                                  StreakService streaks) {
        this.achievements = achievements;
        this.dailyChallenges = dailyChallenges;
        this.streaks = streaks;
    }

    /**
     * A mission was solved.
     *
     * <p>Counts as one mission and one puzzle, and carries the actual payout into the
     * XP and coin daily totals - the real amounts, not the mission's catalogue
     * figures, so a bonus-inflated reward is credited as what it was worth.
     *
     * @param earnedXp   the final XP the mission paid, after bonuses
     * @param earnedCoins the final coins the mission paid
     */
    public List<MilestoneUnlock> missionSolved(UUID userId, PlayerProfile profile,
                                               long earnedXp, long earnedCoins) {
        dailyChallenges.recordEvent(userId, DailyMetric.MISSIONS_COMPLETED, 1);
        dailyChallenges.recordEvent(userId, DailyMetric.PUZZLES_SOLVED, 1);
        dailyChallenges.recordEvent(userId, DailyMetric.XP_EARNED, earnedXp);
        dailyChallenges.recordEvent(userId, DailyMetric.COINS_EARNED, earnedCoins);
        return evaluateAll(userId, profile, "mission solved");
    }

    /** A puzzle was solved inside a boss phase. */
    public List<MilestoneUnlock> puzzleSolved(UUID userId, PlayerProfile profile) {
        dailyChallenges.recordEvent(userId, DailyMetric.PUZZLES_SOLVED, 1);
        return evaluateAll(userId, profile, "puzzle solved");
    }

    /** A boss was defeated. */
    public List<MilestoneUnlock> bossDefeated(UUID userId, PlayerProfile profile,
                                              long earnedXp, long earnedCoins) {
        dailyChallenges.recordEvent(userId, DailyMetric.BOSSES_DEFEATED, 1);
        dailyChallenges.recordEvent(userId, DailyMetric.XP_EARNED, earnedXp);
        dailyChallenges.recordEvent(userId, DailyMetric.COINS_EARNED, earnedCoins);
        return evaluateAll(userId, profile, "boss defeated");
    }

    /** An item was bought. */
    public List<MilestoneUnlock> itemPurchased(UUID userId, PlayerProfile profile) {
        return evaluateAll(userId, profile, "item purchased");
    }

    /** A skill was upgraded. */
    public List<MilestoneUnlock> skillUpgraded(UUID userId, PlayerProfile profile) {
        return evaluateAll(userId, profile, "skill upgraded");
    }

    /** Equipment changed. */
    public List<MilestoneUnlock> equipmentChanged(UUID userId, PlayerProfile profile) {
        return evaluateAll(userId, profile, "equipment changed");
    }

    /**
     * Catches up on anything that was due, with no new event.
     *
     * <p>This is what a player reading their achievements or opening the daily page
     * triggers. Because progress is derived from real state, evaluating without an
     * event is not a shortcut around the rules - it is the same computation the
     * event path runs, and it pays exactly what it would have paid then.
     */
    public List<MilestoneUnlock> evaluateOnly(UUID userId, PlayerProfile profile) {
        return evaluateAll(userId, profile, "read");
    }

    private List<MilestoneUnlock> evaluateAll(UUID userId, PlayerProfile profile, String cause) {
        List<MilestoneUnlock> unlocked = new ArrayList<>();

        unlocked.addAll(streaks.recordActivity(userId, profile));
        unlocked.addAll(dailyChallenges.evaluate(userId, profile));

        // Repeat while achievements keep unlocking, so XP paid by one milestone can
        // satisfy a level milestone in the same request. Each pass must find something
        // new to justify another, and the bound stops a pathological catalogue from
        // spinning.
        for (int pass = 0; pass < MAX_ACHIEVEMENT_PASSES; pass++) {
            List<MilestoneUnlock> earned = achievements.evaluate(userId, profile);
            if (earned.isEmpty()) {
                break;
            }
            unlocked.addAll(earned);
        }

        if (!unlocked.isEmpty()) {
            log.debug("Player {} unlocked {} milestone(s) after {}", userId, unlocked.size(), cause);
        }
        return unlocked;
    }
}
