package com.cyberheist.daily;

import com.cyberheist.game.BusinessCalendar;
import com.cyberheist.game.MilestoneKind;
import com.cyberheist.game.MilestoneRewardService;
import com.cyberheist.game.MilestoneUnlock;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.reward.Reward;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity streaks and the handful of rewards attached to them.
 *
 * <p>Streaks are milestone-only in Phase 7: three thresholds, paid once each for the
 * lifetime of the account. There is no escalating daily payout and no
 * "don't break the chain" pressure beyond the streak itself, which is a deliberate
 * choice - a retention system built on a fear of losing something is not a system
 * anyone should want players inside.
 *
 * <p>Which day it is comes from {@link BusinessCalendar}, so the streak cannot be
 * moved by a device clock or a timezone.
 */
@Service
public class StreakService {

    private static final Logger log = LoggerFactory.getLogger(StreakService.class);

    /**
     * The paid thresholds, in days.
     *
     * <p>Hard-coded because this is a short fixed list of tuning values rather than
     * content, and because the milestone amounts belong beside the day counts they
     * are keyed on. {@code streak_milestone_awards} is what stops these repeating.
     */
    private static final int[] MILESTONE_DAYS = {3, 7, 14};

    private static final Reward MILESTONE_THREE = new Reward(0, 25);
    private static final Reward MILESTONE_SEVEN = new Reward(50, 100);
    private static final Reward MILESTONE_FOURTEEN = new Reward(100, 250);

    private final PlayerStreakRepository streaks;
    private final StreakMilestoneAwardRepository awards;
    private final BusinessCalendar calendar;
    private final MilestoneRewardService milestoneRewards;

    public StreakService(PlayerStreakRepository streaks,
                         StreakMilestoneAwardRepository awards,
                         BusinessCalendar calendar,
                         MilestoneRewardService milestoneRewards) {
        this.streaks = streaks;
        this.awards = awards;
        this.calendar = calendar;
        this.milestoneRewards = milestoneRewards;
    }

    /**
     * Records that the player was active today, and pays any milestone now due.
     *
     * <p>Idempotent within a day by construction: a player who plays ten missions has
     * their streak recorded once, and re-recording on the same business date is a
     * no-op in {@link PlayerStreak#recordActivity}. Reloading a page cannot inflate
     * it.
     *
     * <p>Called from the same places achievements are evaluated, and inside those
     * transactions, so a milestone cannot be paid without the streak that earned it.
     *
     * @return milestones newly paid by this call, for notification
     */
    @Transactional
    public List<MilestoneUnlock> recordActivity(UUID userId, PlayerProfile profile) {
        var today = calendar.today();
        PlayerStreak streak = streaks.findByUserId(userId)
                .orElseGet(() -> PlayerStreak.firstSeen(userId, today));

        int current = streak.recordActivity(today);
        streaks.save(streak);

        List<MilestoneUnlock> unlocked = new ArrayList<>();
        for (int milestone : MILESTONE_DAYS) {
            if (current < milestone) {
                continue;
            }
            if (awards.existsByUserIdAndMilestoneDays(userId, milestone)) {
                // Already paid for this account's lifetime, even if the streak was
                // broken and rebuilt since. Reaching seven days twice pays once.
                continue;
            }

            Reward reward = rewardFor(milestone);
            MilestoneRewardService.Payout payout = milestoneRewards.pay(profile, reward);

            // Recorded and flushed before the unlock is reported, so the unique
            // constraint on (user, milestone) is what actually prevents a second
            // payment under concurrency. Deferring the insert to commit would leave
            // the existsBy check and the insert racing inside this transaction.
            awards.save(new StreakMilestoneAward(userId, milestone, payout.experience(),
                    payout.coins(), calendar.now()));
            awards.flush();

            unlocked.add(new MilestoneUnlock(
                    MilestoneKind.STREAK,
                    "STREAK_" + milestone,
                    milestone + "-day streak",
                    "You showed up " + milestone + " days running.",
                    "◐",
                    payout.experience(),
                    payout.coins(),
                    payout.levelsGained()));

            log.info("Player {} reached a {}-day streak (+{} xp, +{} coins)",
                    userId, milestone, payout.experience(), payout.coins());
        }
        return unlocked;
    }

    /** The streak as reported to the client. */
    @Transactional
    public StreakView current(UUID userId) {
        PlayerStreak streak = streaks.findByUserId(userId).orElse(null);
        int currentStreak = streak == null ? 0 : streak.getCurrentStreak();
        int longestStreak = streak == null ? 0 : streak.getLongestStreak();

        List<StreakView.StreakMilestoneView> milestones = new ArrayList<>();
        for (int days : MILESTONE_DAYS) {
            boolean claimed = awards.existsByUserIdAndMilestoneDays(userId, days);
            milestones.add(new StreakView.StreakMilestoneView(days, rewardFor(days), claimed,
                    currentStreak >= days));
        }

        Integer nextMilestone = null;
        for (int days : MILESTONE_DAYS) {
            if (currentStreak < days) {
                nextMilestone = days;
                break;
            }
        }

        return new StreakView(currentStreak, longestStreak, milestones, nextMilestone);
    }

    private Reward rewardFor(int milestoneDays) {
        return switch (milestoneDays) {
            case 3 -> MILESTONE_THREE;
            case 7 -> MILESTONE_SEVEN;
            case 14 -> MILESTONE_FOURTEEN;
            default -> Reward.none();
        };
    }

    /**
     * A player's current streak length, zero if they have never been active.
     *
     * <p>Read without recording anything, so merely opening a page does not count as
     * activity. Only {@link #recordActivity} advances it, and that is called from real
     * game actions.
     */
    @Transactional(readOnly = true)
    public int currentLength(UUID userId) {
        return streaks.findByUserId(userId).map(PlayerStreak::getCurrentStreak).orElse(0);
    }

    /** Whether a streak row already exists, used by the tests and the service itself. */
    @Transactional(readOnly = true)
    public boolean hasStreak(UUID userId) {
        return streaks.findByUserId(userId).isPresent();
    }

    /** The instant used for milestone awards. */
    Instant now() {
        return calendar.now();
    }
}
