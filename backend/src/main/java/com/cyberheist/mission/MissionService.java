package com.cyberheist.mission;

import com.cyberheist.exception.InsufficientEnergyException;
import com.cyberheist.exception.MissionAlreadyCompletedException;
import com.cyberheist.exception.MissionLockedException;
import com.cyberheist.exception.MissionNotFoundException;
import com.cyberheist.exception.MissionNotStartedException;
import com.cyberheist.exception.MissionUnavailableException;
import com.cyberheist.mission.dto.MissionCompletionResponse;
import com.cyberheist.mission.dto.MissionProgressResponse;
import com.cyberheist.mission.dto.MissionResponse;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.progression.ProgressionResult;
import com.cyberheist.reward.Reward;
import com.cyberheist.reward.RewardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mission lifecycle: browse, start, complete.
 *
 * <p>Every public method takes the caller as {@code userId}, resolved from the
 * security context by the controller. No method accepts a client-supplied user
 * id, reward amount or level, which is what guarantees the client cannot decide
 * its own payout.
 *
 * <p>Start and complete each run in a single transaction, so energy deduction
 * and the progress transition either both apply or neither does.
 */
@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final MissionRepository missionRepository;
    private final MissionProgressRepository progressRepository;
    private final PlayerProfileRepository profileRepository;
    private final RewardService rewardService;

    private final Clock clock;

    @Autowired
    public MissionService(MissionRepository missionRepository,
                          MissionProgressRepository progressRepository,
                          PlayerProfileRepository profileRepository,
                          RewardService rewardService) {
        this(missionRepository, progressRepository, profileRepository, rewardService, Clock.systemUTC());
    }

    MissionService(MissionRepository missionRepository,
                   MissionProgressRepository progressRepository,
                   PlayerProfileRepository profileRepository,
                   RewardService rewardService,
                   Clock clock) {
        this.missionRepository = missionRepository;
        this.progressRepository = progressRepository;
        this.profileRepository = profileRepository;
        this.rewardService = rewardService;
        this.clock = clock;
    }

    /**
     * Lists the missions available to a player, decorated with their own status.
     *
     * <p>Inactive missions are hidden: a player cannot see content that has been
     * retired.
     */
    @Transactional(readOnly = true)
    public List<MissionResponse> listMissions(UUID userId, MissionCategory category) {
        PlayerProfile profile = requireProfile(userId);

        List<Mission> missions = (category == null)
                ? missionRepository.findByActiveTrueOrderByRequiredLevelAscXpRewardAsc()
                : missionRepository.findByActiveTrueAndCategoryOrderByRequiredLevelAscXpRewardAsc(category);

        Map<UUID, MissionProgress> progressByMission = loadProgressMap(userId);

        return missions.stream()
                .map(mission -> MissionResponse.of(
                        mission,
                        profile.getLevel(),
                        statusOf(progressByMission.get(mission.getId()))))
                .toList();
    }

    /** Returns one mission with the caller's own progress. */
    @Transactional(readOnly = true)
    public MissionResponse getMission(UUID userId, UUID missionId) {
        PlayerProfile profile = requireProfile(userId);
        Mission mission = requireMission(missionId);

        MissionProgress progress = progressRepository.findByUserIdAndMissionId(userId, missionId)
                .orElse(null);

        return MissionResponse.of(mission, profile.getLevel(), statusOf(progress));
    }

    /** The caller's progress on one mission, or NOT_STARTED when there is none. */
    @Transactional(readOnly = true)
    public MissionProgressResponse getProgress(UUID userId, UUID missionId) {
        MissionProgress progress = progressRepository.findByUserIdAndMissionId(userId, missionId)
                .orElse(null);
        return toProgressResponse(missionId, progress);
    }
    /**
     * Starts a mission, deducting its energy cost atomically with the transition.
     *
     * <p>Checks are ordered so that a rejected attempt costs the player nothing:
     * energy is only spent once every precondition has passed.
     *
     * @return the mission with its new {@code IN_PROGRESS} status
     * @throws MissionUnavailableException       if the mission is retired
     * @throws MissionLockedException            if below the required level
     * @throws InsufficientEnergyException       if the cost cannot be met
     * @throws MissionAlreadyCompletedException if it is already finished
     */
    @Transactional
    public MissionResponse startMission(UUID userId, UUID missionId) {
        Mission mission = requireMission(missionId);
        PlayerProfile profile = requireProfile(userId);

        if (!mission.isActive()) {
            throw new MissionUnavailableException("This mission is not currently available");
        }
        if (!mission.isUnlockedFor(profile.getLevel())) {
            throw new MissionLockedException(
                    "Requires level %d".formatted(mission.getRequiredLevel()));
        }

        MissionProgress progress = progressRepository
                .findByUserIdAndMissionIdForUpdate(userId, missionId)
                .orElseGet(() -> createProgress(userId, missionId));

        if (progress.getStatus().isCompleted()) {
            throw new MissionAlreadyCompletedException("This mission has already been completed");
        }
        if (profile.getEnergy() < mission.getEnergyCost()) {
            throw new InsufficientEnergyException(
                    "Not enough energy: this mission costs %d".formatted(mission.getEnergyCost()));
        }

        // Energy is spent on start rather than on completion, so an abandoned
        // mission still costs the player. Both writes share this transaction.
        profile.spendEnergy(mission.getEnergyCost());
        progress.start(clock.instant());

        profileRepository.save(profile);
        progressRepository.save(progress);

        log.info("Player {} started mission {} (energy left {})",
                userId, mission.getCode(), profile.getEnergy());

        return MissionResponse.of(mission, profile.getLevel(), progress.getStatus());
    }

    /**
     * Completes a mission and pays its server-defined reward.
     *
     * <p><strong>Duplicate submissions are safe.</strong> The progress row is
     * locked pessimistically for the duration of the transaction, so two
     * simultaneous completion requests are serialised: the first transitions the
     * row to {@code COMPLETED} and pays out, and the second observes that state
     * and returns the existing result <em>without</em> granting anything.
     *
     * @return the payout and the resulting progression
     * @throws MissionNotStartedException if the mission was never started
     */
    @Transactional
    public MissionCompletionResponse completeMission(UUID userId, UUID missionId) {
        Mission mission = requireMission(missionId);
        PlayerProfile profile = requireProfile(userId);

        MissionProgress progress = progressRepository
                .findByUserIdAndMissionIdForUpdate(userId, missionId)
                .orElseThrow(() -> new MissionNotStartedException("This mission has not been started"));

        MissionCompletionResponse.MissionSummary summary =
                new MissionCompletionResponse.MissionSummary(mission.getId(), mission.getCode(), mission.getTitle());

        if (progress.getStatus().isCompleted()) {
            // Idempotent replay: report the state, award nothing.
            log.info("Ignoring repeat completion of mission {} by player {}", mission.getCode(), userId);
            return new MissionCompletionResponse(
                    summary,
                    new MissionCompletionResponse.Rewards(0L, 0L),
                    noChangeProgression(profile),
                    playerState(profile),
                    true);
        }

        if (progress.getStatus() != MissionStatus.IN_PROGRESS) {
            throw new MissionNotStartedException("This mission has not been started");
        }

        // Rewards come from the mission row, never from the request.
        Reward reward = new Reward(mission.getXpReward(), mission.getCoinReward());
        ProgressionResult progression = rewardService.grant(profile, reward);

        progress.complete(clock.instant());
        profileRepository.save(profile);
        progressRepository.save(progress);

        log.info("Player {} completed mission {} (+{} xp, +{} coins, level {} -> {})",
                userId, mission.getCode(), reward.experience(), reward.coins(),
                progression.levelBefore(), progression.levelAfter());

        return new MissionCompletionResponse(
                summary,
                new MissionCompletionResponse.Rewards(reward.experience(), reward.coins()),
                progression,
                playerState(profile),
                false);
    }
    private Mission requireMission(UUID missionId) {
        return missionRepository.findById(missionId)
                .orElseThrow(() -> new MissionNotFoundException("Mission not found"));
    }

    private PlayerProfile requireProfile(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new MissionNotFoundException("Player profile not found"));
    }

    /**
     * Creates a progress row for a mission the player has never touched.
     *
     * <p>Racy on first use: two simultaneous starts could both try to insert.
     * The unique constraint on (user_id, mission_id) rejects the loser, which the
     * caller re-reads.
     */
    private MissionProgress createProgress(UUID userId, UUID missionId) {
        return new MissionProgress(UUID.randomUUID(), userId, missionId);
    }

    /** Indexes the player's progress by mission id for decorating the catalogue. */
    private Map<UUID, MissionProgress> loadProgressMap(UUID userId) {
        Map<UUID, MissionProgress> byMission = new HashMap<>();
        for (MissionProgress progress : progressRepository.findByUserId(userId)) {
            byMission.put(progress.getMissionId(), progress);
        }
        return byMission;
    }

    /** A mission with no stored progress is {@code NOT_STARTED}. */
    private MissionStatus statusOf(MissionProgress progress) {
        return progress == null ? MissionStatus.NOT_STARTED : progress.getStatus();
    }

    private MissionProgressResponse toProgressResponse(UUID missionId, MissionProgress progress) {
        if (progress == null) {
            return new MissionProgressResponse(missionId, MissionStatus.NOT_STARTED.name(), 0, null, null);
        }
        return new MissionProgressResponse(
                missionId,
                progress.getStatus().name(),
                progress.getAttemptCount(),
                progress.getStartedAt(),
                progress.getCompletedAt());
    }

    /** Progression snapshot for a replayed completion, where nothing changed. */
    private ProgressionResult noChangeProgression(PlayerProfile profile) {
        return rewardService.currentProgression(profile);
    }

    private MissionCompletionResponse.PlayerState playerState(PlayerProfile profile) {
        return new MissionCompletionResponse.PlayerState(
                profile.getLevel(),
                profile.getExperience(),
                profile.getCoins(),
                profile.getEnergy());
    }
}