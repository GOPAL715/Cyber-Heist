package com.cyberheist.achievement;

import com.cyberheist.game.BusinessCalendar;
import com.cyberheist.game.MilestoneKind;
import com.cyberheist.game.MilestoneUnlock;
import com.cyberheist.game.MilestoneRewardService;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import java.time.Instant;
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
 * The single place achievements are evaluated, unlocked and paid.
 *
 * <p>Every other service that wants a milestone credited calls
 * {@link #evaluate(UUID, PlayerProfile)} and nothing else. No controller contains
 * achievement logic, and no achievement logic lives in a mission, a boss or a
 * purchase - those services only say "something happened that might matter".
 *
 * <h2>Why unlocking is safe to run on every action</h2>
 * Progress is re-measured from the real tables on every call rather than
 * incremented, so evaluating twice is free and evaluating a hundred times pays
 * nothing extra. Three things then hold without any special casing:
 * <ul>
 *   <li><b>Idempotency.</b> {@link PlayerAchievement#unlock} returns {@code false} for
 *       an already-unlocked milestone, and the reward is only granted when it
 *       returns {@code true}. A retried request therefore re-reads the same facts,
 *       finds the milestone already unlocked, and pays nothing.</li>
 *   <li><b>No progress-without-reward.</b> Unlock and reward happen in the caller's
 *       transaction, so an achievement can never be marked earned while its payout
 *       is lost, nor paid while the unlock is missing.</li>
 *   <li><b>Retroactive credit.</b> Because progress is derived, a player who already
 *       satisfies a milestone is credited on their next evaluation rather than
 *       needing the original event to be replayed.</li>
 * </ul>
 *
 * <h2>Locking</h2>
 * The profile is locked first, matching the convention the rest of the application
 * follows. Milestone rows are then taken in catalogue order so that two concurrent
 * evaluations of the same player cannot deadlock against each other by acquiring the
 * same two rows in opposite orders.
 */
@Service
public class AchievementService {

    private static final Logger log = LoggerFactory.getLogger(AchievementService.class);

    private final AchievementRepository achievements;
    private final PlayerAchievementRepository playerAchievements;
    private final PlayerProfileRepository profiles;
    private final PlayerStatsService stats;
    private final MilestoneRewardService milestoneRewards;
    private final BusinessCalendar calendar;

    public AchievementService(AchievementRepository achievements,
                              PlayerAchievementRepository playerAchievements,
                              PlayerProfileRepository profiles,
                              PlayerStatsService stats,
                              MilestoneRewardService milestoneRewards,
                              BusinessCalendar calendar) {
        this.achievements = achievements;
        this.playerAchievements = playerAchievements;
        this.profiles = profiles;
        this.stats = stats;
        this.milestoneRewards = milestoneRewards;
        this.calendar = calendar;
    }

    /**
     * Evaluates the whole catalogue and pays whatever is newly due.
     *
     * <p>The single entry point Phase 7 event sites use. It is safe to call from
     * inside an existing transaction - the caller's work and this work then commit
     * together, which is the point - and equally safe to call on its own when a
     * player reads their achievements.
     *
     * @return every milestone unlocked by this call, in catalogue order; empty when
     *         nothing new was due
     */
    @Transactional
    public List<MilestoneUnlock> evaluate(UUID userId, PlayerProfile callerProfile) {
        // Profile first, always. If the caller already holds this lock the query is a
        // no-op re-read of the same managed instance; if it does not, this is what
        // makes two concurrent evaluations serialise.
        PlayerProfile profile = callerProfile != null
                ? callerProfile
                : profiles.findByUserIdForUpdate(userId).orElse(null);
        if (profile == null) {
            return List.of();
        }

        List<Achievement> catalogue = achievements.findByActiveTrueOrderByCategoryAscSortOrderAsc();
        if (catalogue.isEmpty()) {
            return List.of();
        }

        PlayerStats measured = stats.measure(userId, stats.requirementsOf(catalogue), profile);

        // One read of the player's existing rows, so the loop below does not query
        // per achievement.
        Map<UUID, PlayerAchievement> existing = new HashMap<>();
        for (PlayerAchievement row : playerAchievements.findByUserId(userId)) {
            existing.put(row.getAchievementId(), row);
        }

        Instant now = calendar.now();
        List<MilestoneUnlock> unlocked = new ArrayList<>();

        for (Achievement achievement : catalogue) {
            PlayerAchievement row = existing.get(achievement.getId());
            if (row == null) {
                // Tracked from zero, then immediately brought up to the measured
                // value below. Creating the row is what makes a milestone that was
                // already satisfied show as earned rather than empty.
                row = PlayerAchievement.tracked(userId, achievement);
                existing.put(achievement.getId(), row);
            }

            long value = measured.of(achievement.getRequirement());
            row.record(value, achievement);

            if (!achievement.isSatisfiedBy(value) || !row.unlock(achievement, now)) {
                playerAchievements.save(row);
                continue;
            }

            // This call is the one that crossed the line, so this is the one that pays.
            MilestoneRewardService.Payout payout =
                    milestoneRewards.pay(profile, achievement.reward());
            playerAchievements.save(row);

            unlocked.add(new MilestoneUnlock(
                    MilestoneKind.ACHIEVEMENT,
                    achievement.getCode(),
                    achievement.getName(),
                    achievement.getDescription(),
                    achievement.getIcon(),
                    payout.experience(),
                    payout.coins(),
                    payout.levelsGained()));

            log.info("Player {} unlocked achievement {} (+{} xp, +{} coins)",
                    userId, achievement.getCode(), achievement.getXpReward(),
                    achievement.getCoinReward());
        }

        if (!unlocked.isEmpty()) {
            // The profile was credited by the reward path; persist the level and
            // balance changes it made.
            profiles.save(profile);
        }
        return unlocked;
    }

    /** Evaluates using the caller's already-loaded profile. */
    @Transactional
    public List<MilestoneUnlock> evaluate(UUID userId) {
        return evaluate(userId, null);
    }

    /** The active catalogue with this player's state against each entry. */
    @Transactional(readOnly = true)
    public List<AchievementView> catalogueFor(UUID userId, PlayerProfile profile) {
        List<Achievement> catalogue = achievements.findByActiveTrueOrderByCategoryAscSortOrderAsc();
        PlayerStats measured = stats.measure(userId, stats.requirementsOf(catalogue), profile);

        Map<UUID, PlayerAchievement> existing = new HashMap<>();
        for (PlayerAchievement row : playerAchievements.findByUserId(userId)) {
            existing.put(row.getAchievementId(), row);
        }

        List<AchievementView> views = new ArrayList<>();
        for (Achievement achievement : catalogue) {
            PlayerAchievement row = existing.get(achievement.getId());
            views.add(AchievementView.of(achievement, row, measured.of(achievement.getRequirement())));
        }
        return views;
    }

    /** One achievement's definition and this player's state against it. */
    @Transactional(readOnly = true)
    public Optional<AchievementView> findByCode(UUID userId, PlayerProfile profile, String code) {
        Optional<Achievement> achievement = achievements.findByCodeAndActiveTrue(code);
        if (achievement.isEmpty()) {
            return Optional.empty();
        }
        Achievement found = achievement.get();
        PlayerStats measured = stats.measure(userId,
                java.util.EnumSet.of(found.getRequirement()), profile);
        PlayerAchievement row = playerAchievements
                .findByUserIdAndAchievementId(userId, found.getId())
                .orElse(null);
        return Optional.of(AchievementView.of(found, row, measured.of(found.getRequirement())));
    }

    /**
     * The most recently unlocked milestones, newest first.
     *
     * <p>Bounded by the caller. Paired with the catalogue lookup so each row can be
     * reported with its name and reward rather than as a bare id.
     */
    @Transactional(readOnly = true)
    public List<AchievementView> recentFor(UUID userId, PlayerProfile profile, int limit) {
        List<PlayerAchievement> recent = playerAchievements.findRecentUnlocks(userId,
                org.springframework.data.domain.PageRequest.of(0, limit));
        if (recent.isEmpty()) {
            return List.of();
        }

        Map<UUID, Achievement> byId = new HashMap<>();
        for (Achievement achievement : achievements.findAll()) {
            byId.put(achievement.getId(), achievement);
        }

        List<AchievementView> views = new ArrayList<>();
        for (PlayerAchievement row : recent) {
            Achievement achievement = byId.get(row.getAchievementId());
            if (achievement != null) {
                views.add(AchievementView.of(achievement, row, achievement.getRequirementValue()));
            }
        }
        return views;
    }
}
