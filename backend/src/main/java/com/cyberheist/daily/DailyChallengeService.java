package com.cyberheist.daily;

import com.cyberheist.game.BusinessCalendar;
import com.cyberheist.game.GameProperties;
import com.cyberheist.game.MilestoneKind;
import com.cyberheist.game.MilestoneRewardService;
import com.cyberheist.game.MilestoneUnlock;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Today's objectives, a player's progress against them, and the rewards.
 *
 * <h2>How a day is created</h2>
 * Lazily, on the first request for that business date, and then frozen. There is no
 * midnight job and no scheduler anywhere in Phase 7: a day comes into existence when
 * somebody asks for it, from a deterministic selection, and the same three objectives
 * are then served to everyone until the date rolls over. Yesterday's rows are simply
 * never queried again.
 *
 * <h2>How progress moves</h2>
 * Only from {@link PlayerDailyCounter} totals, which are incremented inside the
 * transaction that performed the real event. There is no endpoint that writes
 * progress, so "earn 100 XP today" cannot be satisfied by asking for it.
 *
 * <h2>How a reward is paid once</h2>
 * {@link DailyChallengeProgress#completeIfSatisfied} returns {@code true} only for
 * the evaluation that actually crossed the line. The reward is granted on that
 * return value alone, inside the caller's transaction, so re-evaluating after the
 * fact - which the client is free to do, and which a replayed request causes - finds
 * the objective complete, gets {@code false} and pays nothing.
 */
@Service
public class DailyChallengeService {

    private static final Logger log = LoggerFactory.getLogger(DailyChallengeService.class);

    private final DailyChallengeRepository challenges;
    private final DailyChallengeDefinitionRepository definitions;
    private final DailyChallengeProgressRepository progress;
    private final PlayerDailyCounterRepository counters;
    private final PlayerProfileRepository profiles;
    private final DailyChallengeSelector selector;
    private final BusinessCalendar calendar;
    private final GameProperties properties;
    private final MilestoneRewardService milestoneRewards;

    public DailyChallengeService(DailyChallengeRepository challenges,
                                 DailyChallengeDefinitionRepository definitions,
                                 DailyChallengeProgressRepository progress,
                                 PlayerDailyCounterRepository counters,
                                 PlayerProfileRepository profiles,
                                 DailyChallengeSelector selector,
                                 BusinessCalendar calendar,
                                 GameProperties properties,
                                 MilestoneRewardService milestoneRewards) {
        this.challenges = challenges;
        this.definitions = definitions;
        this.progress = progress;
        this.counters = counters;
        this.profiles = profiles;
        this.selector = selector;
        this.calendar = calendar;
        this.properties = properties;
        this.milestoneRewards = milestoneRewards;
    }

    /**
     * Today's objectives, creating the day if this is the first request for it.
     *
     * <p>Concurrency: two players opening the page at the same moment on a fresh date
     * both find the day missing and both try to create it. One wins; the other hits
     * the unique constraint, discards its own rows and re-reads. The discarded rows
     * are never visible because the losing transaction is the one that rolls back.
     */
    @Transactional
    public List<DailyChallenge> today() {
        return forDate(calendar.today());
    }

    /**
     * The frozen objective set for a date, creating it if absent.
     *
     * <p>Creation serialises on a lock over the definition pool rather than on
     * catching a unique-constraint violation. That is not a stylistic preference: a
     * constraint failure inside a caller's transaction would mark it rollback-only,
     * so the player who lost the race would lose whatever real game action they were
     * performing when they happened to open the daily page. Queueing on the pool
     * lock means the loser simply re-reads and finds the winner's rows.
     */
    @Transactional
    public List<DailyChallenge> forDate(LocalDate date) {
        List<DailyChallenge> existing = challenges.findByChallengeDateOrderByCodeAsc(date);
        if (!existing.isEmpty()) {
            // Includes the case where the pool has since shrunk below a day's set.
            // Serving the frozen rows is better than reshuffling objectives a player
            // may already have completed.
            return existing;
        }

        int perDay = properties.challengesPerDay();
        List<DailyChallengeDefinition> pool = definitions.findActiveForUpdate();

        // Re-check under the lock: a competing request may have created this date
        // while we were waiting for it.
        List<DailyChallenge> raced = challenges.findByChallengeDateOrderByCodeAsc(date);
        if (!raced.isEmpty()) {
            return raced;
        }

        return materialise(date, perDay, pool);
    }

    private List<DailyChallenge> materialise(LocalDate date, int perDay,
                                             List<DailyChallengeDefinition> pool) {
        if (pool.isEmpty()) {
            return List.of();
        }
        List<DailyChallengeDefinition> chosen =
                selector.select(date, properties.dailySeedKey(), pool, perDay);

        Instant now = calendar.now();
        List<DailyChallenge> created = new ArrayList<>();
        for (DailyChallengeDefinition definition : chosen) {
            DailyChallenge row = DailyChallenge.forDate(date, definition, now);
            challenges.save(row);
            created.add(row);
        }
        challenges.flush();
        log.info("Materialised {} daily challenges for business date {}", created.size(), date);
        return created;
    }

    /**
     * Credits a real event against today's totals.
     *
     * <p>Called from inside the transaction that performed the event, which is what
     * makes a daily total and the event that caused it commit or fail together. Only
     * positive amounts are accepted, so a daily objective can never be un-earned by
     * spending or dying.
     *
     * @param amount how much happened - one mission, or that mission's XP payout
     */
    @Transactional
    public void recordEvent(UUID userId, DailyMetric metric, long amount) {
        if (amount <= 0) {
            return;
        }
        LocalDate today = calendar.today();
        PlayerDailyCounter counter = counters
                .findForUpdate(userId, today, metric)
                .orElseGet(() -> PlayerDailyCounter.empty(userId, today, metric));
        counter.add(amount);
        counters.save(counter);
    }

    /**
     * Re-reads today's totals into the player's progress rows, completing and paying
     * anything newly due.
     *
     * <p>The only thing that can move a daily challenge forward. It reads counters,
     * never a request body, which is what makes {@code POST /daily/evaluate} safe to
     * expose: it asks the server to look, and the server decides.
     *
     * @return every objective completed by this call, for notification
     */
    @Transactional
    public List<MilestoneUnlock> evaluate(UUID userId, PlayerProfile callerProfile) {
        PlayerProfile profile = callerProfile != null
                ? callerProfile
                : profiles.findByUserIdForUpdate(userId).orElse(null);
        if (profile == null) {
            return List.of();
        }

        LocalDate today = calendar.today();
        List<DailyChallenge> todays = forDate(today);
        if (todays.isEmpty()) {
            return List.of();
        }

        Map<DailyMetric, Long> totals = readTotals(userId, today);
        Map<UUID, DailyChallengeProgress> existing = loadProgress(userId);
        Instant now = calendar.now();

        List<MilestoneUnlock> completed = new ArrayList<>();

        for (DailyChallenge challenge : todays) {
            DailyChallengeProgress row = existing.get(challenge.getId());
            if (row == null) {
                row = DailyChallengeProgress.untouched(userId, challenge);
                existing.put(challenge.getId(), row);
            }

            long measured = totals.getOrDefault(challenge.getRequirement(), 0L);

            // True only for the evaluation that actually crossed the requirement, so
            // this branch is the only one that can pay.
            if (row.completeIfSatisfied(measured, challenge, now)) {
                MilestoneRewardService.Payout payout =
                        milestoneRewards.pay(profile, challenge.reward());
                progress.save(row);
                completed.add(new MilestoneUnlock(
                        MilestoneKind.DAILY_CHALLENGE,
                        challenge.getCode(),
                        challenge.getTitle(),
                        challenge.getDescription(),
                        "◐",
                        payout.experience(),
                        payout.coins(),
                        payout.levelsGained()));
                log.info("Player {} completed daily challenge {} (+{} xp, +{} coins)",
                        userId, challenge.getCode(), challenge.getXpReward(),
                        challenge.getCoinReward());
            } else {
                // Either still short of the requirement, or already complete - in
                // which case record() is a no-op and the row is left exactly as the
                // player last saw it.
                row.record(measured, challenge);
                progress.save(row);
            }
        }

        if (!completed.isEmpty()) {
            profiles.save(profile);
        }
        return completed;
    }

    /** Today's objectives with this player's progress against each. */
    @Transactional
    public List<DailyChallengeView> todayFor(UUID userId) {
        LocalDate today = calendar.today();
        List<DailyChallenge> todays = forDate(today);
        Map<UUID, DailyChallengeProgress> existing = loadProgress(userId);
        Map<DailyMetric, Long> totals = readTotals(userId, today);

        List<DailyChallengeView> views = new ArrayList<>();
        for (DailyChallenge challenge : todays) {
            DailyChallengeProgress row = existing.get(challenge.getId());
            long measured = totals.getOrDefault(challenge.getRequirement(), 0L);
            views.add(DailyChallengeView.of(challenge, row, measured));
        }
        return views;
    }

    /** The business date in force, as the client should display it. */
    @Transactional(readOnly = true)
    public LocalDate businessDate() {
        return calendar.today();
    }

    /** How many objectives a player has ever completed. */
    @Transactional(readOnly = true)
    public long lifetimeCompletions(UUID userId) {
        return progress.countByUserIdAndCompletedTrue(userId);
    }

    private Map<DailyMetric, Long> readTotals(UUID userId, LocalDate date) {
        Map<DailyMetric, Long> totals = new HashMap<>();
        for (PlayerDailyCounter counter : counters.findByUserIdAndBusinessDate(userId, date)) {
            totals.put(counter.getMetric(), counter.getValue());
        }
        return totals;
    }

    private Map<UUID, DailyChallengeProgress> loadProgress(UUID userId) {
        Map<UUID, DailyChallengeProgress> existing = new HashMap<>();
        for (DailyChallengeProgress row : progress.findByUserId(userId)) {
            existing.put(row.getDailyChallengeId(), row);
        }
        return existing;
    }

    /** A player's progress on one objective, if they have opened it. */
    @Transactional(readOnly = true)
    public Optional<DailyChallengeProgress> progressFor(UUID userId, UUID challengeId) {
        return progress.findByUserIdAndDailyChallengeId(userId, challengeId);
    }
}
