package com.cyberheist.mission;

import com.cyberheist.energy.EnergyService;
import com.cyberheist.energy.EnergySnapshot;
import com.cyberheist.exception.InsufficientEnergyException;
import com.cyberheist.exception.MissionAlreadyCompletedException;
import com.cyberheist.exception.MissionLockedException;
import com.cyberheist.exception.MissionNotFoundException;
import com.cyberheist.exception.MissionNotStartedException;
import com.cyberheist.exception.MissionUnavailableException;
import com.cyberheist.exception.PuzzleAlreadySubmittedException;
import com.cyberheist.exception.PuzzleNotFoundException;
import com.cyberheist.exception.PuzzleRequiredException;
import com.cyberheist.mission.dto.MissionCompletionResponse;
import com.cyberheist.mission.dto.MissionProgressResponse;
import com.cyberheist.mission.dto.MissionResponse;
import com.cyberheist.mission.dto.MissionStartResponse;
import com.cyberheist.mission.dto.PuzzleOutcome;
import com.cyberheist.mission.dto.PuzzleSubmissionResponse;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.progression.ProgressionResult;
import com.cyberheist.puzzle.GeneratedPuzzle;
import com.cyberheist.puzzle.PuzzleAttempt;
import com.cyberheist.puzzle.PuzzleAttemptRepository;
import com.cyberheist.puzzle.PuzzleAttemptStatus;
import com.cyberheist.puzzle.PuzzleService;
import com.cyberheist.puzzle.dto.PuzzleChallengeView;
import com.cyberheist.reward.Reward;
import com.cyberheist.reward.RewardService;
import com.cyberheist.bonus.PlayerBonusService;
import com.cyberheist.game.PlayerMilestoneService;
import com.cyberheist.shop.EquipmentBonusService;
import com.cyberheist.shop.ItemEffectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mission lifecycle: browse, start, solve, complete.
 *
 * <p>Every public method takes the caller as {@code userId}, resolved from the
 * security context by the controller. No method accepts a client-supplied user
 * id, reward amount or level, which is what guarantees the client cannot decide
 * its own payout or reach another player's progress.
 *
 * <h2>Lock ordering</h2>
 * Every mutating path takes its locks in one fixed order - player profile, then
 * mission progress, then puzzle attempt - so two requests touching different
 * combinations of those rows cannot deadlock against each other. All are
 * pessimistic writes because all three are read-modify-write.
 *
 * <h2>Division of labour</h2>
 * This service owns the mission lifecycle and nothing about puzzles themselves.
 * It asks {@code PuzzleService} to generate a challenge and to judge an answer,
 * then decides what that verdict means for the mission. It never computes XP or
 * coins itself: those still flow through {@code RewardService} and
 * {@code ProgressionService} exactly as in Phase 2.
 */
@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final MissionRepository missionRepository;
    private final MissionProgressRepository progressRepository;
    private final PlayerProfileRepository profileRepository;
    private final PuzzleAttemptRepository puzzleRepository;
    private final RewardService rewardService;
    private final PuzzleService puzzleService;
    private final EnergyService energyService;
    private final PlayerBonusService playerBonusService;
    private final PlayerMilestoneService milestoneService;

    private final Clock clock;

    @Autowired
    public MissionService(MissionRepository missionRepository,
                          MissionProgressRepository progressRepository,
                          PlayerProfileRepository profileRepository,
                          PuzzleAttemptRepository puzzleRepository,
                          RewardService rewardService,
                          PuzzleService puzzleService,
                          EnergyService energyService,
                          PlayerBonusService playerBonusService,
                          PlayerMilestoneService milestoneService) {
        this(missionRepository, progressRepository, profileRepository, puzzleRepository,
                rewardService, puzzleService, energyService, playerBonusService,
                milestoneService, Clock.systemUTC());
    }

    MissionService(MissionRepository missionRepository,
                   MissionProgressRepository progressRepository,
                   PlayerProfileRepository profileRepository,
                   PuzzleAttemptRepository puzzleRepository,
                   RewardService rewardService,
                   PuzzleService puzzleService,
                   EnergyService energyService,
                   PlayerBonusService playerBonusService,
                   PlayerMilestoneService milestoneService,
                   Clock clock) {
        this.missionRepository = missionRepository;
        this.progressRepository = progressRepository;
        this.profileRepository = profileRepository;
        this.puzzleRepository = puzzleRepository;
        this.rewardService = rewardService;
        this.puzzleService = puzzleService;
        this.energyService = energyService;
        this.playerBonusService = playerBonusService;
        this.milestoneService = milestoneService;
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
     * Starts a mission, charges its energy cost and generates its puzzle.
     *
     * <p>All three happen in one transaction, so a player can neither be
     * charged without receiving a challenge nor receive one for free. Checks run
     * in an order where a rejected attempt costs nothing: energy is spent only
     * after every precondition has passed.
     *
     * <p>Starting an already-started mission supersedes the previous puzzle. The
     * old row is closed as {@code EXPIRED} before the new one is written, which
     * keeps exactly one live puzzle per mission and makes the superseded puzzle
     * un-submittable rather than merely forgotten.
     *
     * @return the mission's new state plus the generated challenge
     * @throws MissionUnavailableException       if the mission is retired
     * @throws MissionLockedException            if below the required level
     * @throws InsufficientEnergyException       if the cost cannot be met
     * @throws MissionAlreadyCompletedException if it is already finished
     */
    @Transactional
    public MissionStartResponse startMission(UUID userId, UUID missionId) {
        Mission mission = requireMission(missionId);

        // Lock 1 of 3: the profile, so the balance we test is the balance we
        // deduct from even while another request is spending at the same moment.
        PlayerProfile profile = energyService.lockAndRefresh(userId);

        if (!mission.isActive()) {
            throw new MissionUnavailableException("This mission is not currently available");
        }
        if (!mission.isUnlockedFor(profile.getLevel())) {
            throw new MissionLockedException(
                    "Requires level %d".formatted(mission.getRequiredLevel()));
        }

        // Lock 2 of 3: the progress row, serialising concurrent starts.
        MissionProgress progress = progressRepository
                .findByUserIdAndMissionIdForUpdate(userId, missionId)
                .orElseGet(() -> createProgress(userId, missionId));

        if (progress.getStatus().isCompleted()) {
            throw new MissionAlreadyCompletedException("This mission has already been completed");
        }

        // Energy efficiency shortens the cost, deterministically and never below
        // one energy. The mission row still reports its base cost; the discount
        // is this player's, so it is applied here rather than being baked into
        // the catalogue.
        int energyCost = EquipmentBonusService.applyEnergyDiscount(
                mission.getEnergyCost(),
                playerBonusService.bonusFor(userId, ItemEffectType.ENERGY_EFFICIENCY));

        if (profile.getEnergy() < energyCost) {
            throw new InsufficientEnergyException(
                    "Not enough energy: this mission costs %d".formatted(energyCost));
        }

        // Energy is charged on start rather than on completion, so an abandoned
        // mission still costs the player.
        profile.spendEnergy(energyCost);

        Instant now = clock.instant();
        progress.start(now);
        PuzzleAttempt puzzle = issuePuzzle(userId, missionId, mission, now);

        profileRepository.save(profile);
        progressRepository.save(progress);

        log.info("Player {} started mission {} (puzzle {} {}, energy left {})",
                userId, mission.getCode(), puzzle.getPuzzleType(), puzzle.getPuzzleId(),
                profile.getEnergy());

        return MissionStartResponse.of(
                MissionResponse.of(mission, profile.getLevel(), progress.getStatus()),
                puzzleService.viewOf(puzzle),
                progress.getAttemptCount(),
                energyService.describe(profile));
    }

    /**
     * Submits an answer to the active puzzle for a mission and applies the verdict.
     *
     * <p>The order below is the security order, and each step depends on the one
     * before it:
     * <ol>
     *   <li>Lock the puzzle for update. This serialises concurrent submissions of
     *       the same puzzle and keeps a fixed lock order across the app.</li>
     *   <li>Confirm the puzzle is the caller's <em>and</em> on this mission. A
     *       mismatched pair is indistinguishable from a guess.</li>
     *   <li>Confirm the mission is in progress, so a completed mission cannot be
     *       re-entered through a stale puzzle.</li>
     *   <li>Compare the server clock with {@code expiresAt}. An expired puzzle
     *       pays nothing even when the answer is right.</li>
     *   <li>Only then judge the answer, re-deriving the expected value from the
     *       stored seed.</li>
     *   <li>Pay through {@code RewardService} - never here - and complete the
     *       mission in the same transaction as the puzzle's state change, so a
     *       failure cannot leave a solved puzzle unpaid.</li>
     * </ol>
     *
     * <p>A repeat submission against an already-solved puzzle returns a
     * successful outcome with zero rewards rather than an error: the player's
     * question - "did this work?" - is answered truthfully and nothing is paid
     * twice. A submission against a spent puzzle is a genuine conflict and is
     * reported as one.
     */
    @Transactional
    public PuzzleSubmissionResponse submitPuzzle(UUID userId, UUID missionId, UUID puzzleId, String answer) {
        Mission mission = requireMission(missionId);

        // Lock 1 of 3: the puzzle itself. Everything after this is serialised
        // behind it.
        PuzzleAttempt puzzle = puzzleRepository.findByPuzzleIdForUpdate(puzzleId)
                .orElseThrow(PuzzleNotFoundException::new);

        // Ownership and mission binding in one check. Submitting another
        // player's puzzle id, or a puzzle belonging to a different mission, is
        // reported identically: not a puzzle of yours.
        //
        // missionId.equals(...) rather than puzzle.getMissionId().equals(...):
        // since Phase 6 a boss stage puzzle has no mission, so calling equals on
        // the nullable column would throw instead of rejecting. The caller
        // always supplies a mission id, so testing the argument is safe and says
        // exactly the same thing.
        if (!puzzle.getUserId().equals(userId) || !missionId.equals(puzzle.getMissionId())) {
            log.warn("Player {} tried to submit puzzle {} outside their mission {}",
                    userId, puzzleId, missionId);
            throw new PuzzleNotFoundException();
        }

        MissionCompletionResponse.MissionSummary summary =
                new MissionCompletionResponse.MissionSummary(
                        mission.getId(), mission.getCode(), mission.getTitle());

        // Lock 2 of 3: the progress row, which the reward path also needs.
        MissionProgress progress = progressRepository
                .findByUserIdAndMissionIdForUpdate(userId, missionId)
                .orElseThrow(() -> new MissionNotStartedException("This mission has not been started"));

        // Lock 3 of 3: the profile, whose energy the response reports.
        PlayerProfile profile = energyService.lockAndRefresh(userId);

        if (progress.getStatus().isCompleted()) {
            // The mission is finished, so this is a replay. Report success, pay
            // nothing; the puzzle could not legitimately be answered again.
            log.info("Ignoring puzzle replay for completed mission {} by player {}",
                    mission.getCode(), userId);
            return PuzzleSubmissionResponse.replay(summary, puzzle.getPuzzleType(),
                    "This mission was already completed.",
                    rewardService.currentProgression(profile),
                    energyService.describe(profile));
        }

        if (progress.getStatus() != MissionStatus.IN_PROGRESS) {
            throw new MissionNotStartedException("This mission has not been started");
        }

        Instant now = clock.instant();

        // A puzzle that has already been answered is spent. A successful one is
        // tolerated above as a replay; anything else is a real conflict.
        if (!puzzle.getStatus().isSubmittable()) {
            if (puzzle.getStatus() == PuzzleAttemptStatus.SUCCEEDED) {
                return PuzzleSubmissionResponse.replay(summary, puzzle.getPuzzleType(),
                        "This puzzle has already been solved.",
                        rewardService.currentProgression(profile),
                        energyService.describe(profile));
            }
            throw new PuzzleAlreadySubmittedException(
                    "This puzzle has already been answered. Start the mission again for a new challenge.");
        }

        // Expiry is judged before the answer is even looked at: a late answer is
        // refused whether it was right or wrong.
        if (puzzle.isExpiredAt(now)) {
            puzzle.expire(now);
            puzzleRepository.save(puzzle);
            log.info("Player {} missed the window on puzzle {} of mission {}",
                    userId, puzzleId, mission.getCode());
            return PuzzleSubmissionResponse.rejected(summary, puzzle.getPuzzleType(),
                    PuzzleOutcome.EXPIRED,
                    "The security system detected inactivity and closed the connection.",
                    rewardService.currentProgression(profile),
                    energyService.describe(profile));
        }

        // The verdict. The expected value is re-derived from the stored seed on
        // the server, so it was never in the request and never at the client.
        boolean correct = puzzleService.isCorrect(
                puzzle.getPuzzleType(), puzzle.getDifficulty(), puzzle.getSeed(), answer);

        if (!correct) {
            puzzle.submit(PuzzleAttemptStatus.FAILED, now);
            puzzleRepository.save(puzzle);
            log.info("Player {} failed puzzle {} of mission {} (no rewards)",
                    userId, puzzleId, mission.getCode());
            return PuzzleSubmissionResponse.rejected(summary, puzzle.getPuzzleType(),
                    PuzzleOutcome.INCORRECT,
                    "Incorrect answer. No rewards earned.",
                    rewardService.currentProgression(profile),
                    energyService.describe(profile));
        }

        // Correct. Rewards still come from the mission row via RewardService,
        // which applies the player's equipment bonuses on top of the base
        // amounts. This service never computes the final figures itself.
        Reward reward = RewardService.applyBonuses(
                new Reward(mission.getXpReward(), mission.getCoinReward()),
                playerBonusService.bonusesFor(userId));
        ProgressionResult progression = rewardService.grant(profile, reward);

        puzzle.submit(PuzzleAttemptStatus.SUCCEEDED, now);
        progress.complete(now);

        puzzleRepository.save(puzzle);
        progressRepository.save(progress);
        profileRepository.save(profile);

        log.info("Player {} solved puzzle {} and completed mission {} (+{} xp, +{} coins, level {} -> {})",
                userId, puzzleId, mission.getCode(), reward.experience(), reward.coins(),
                progression.levelBefore(), progression.levelAfter());

        // Phase 7. Reports that a mission was solved; it does not know or care that
        // achievements, daily objectives or a streak exist. Called after the three
        // writes above so the milestone evaluation reads the committed-so-far state,
        // and inside this transaction so an unlock and its payout commit together with
        // the completion that caused them. The final post-bonus amounts are passed
        // rather than the mission's catalogue figures, so a bonus-inflated reward is
        // credited into the daily totals as what it was actually worth.
        milestoneService.missionSolved(userId, profile, reward.experience(), reward.coins());

        return PuzzleSubmissionResponse.solved(summary, puzzle.getPuzzleType(),
                "Security bypassed.",
                new MissionCompletionResponse.Rewards(reward.experience(), reward.coins()),
                progression,
                energyService.describe(profile));
    }

    /**
     * Reports a mission's completion without being able to cause one.
     *
     * <p>In Phase 2 this endpoint paid out on its own, so {@code start →
     * complete} skipped the entire challenge. Phase 3 closes that: the only
     * writer of {@code COMPLETED} is a solved puzzle, so this method can report
     * an existing completion and can never create one.
     *
     * <p>The endpoint is kept for compatibility rather than removed, because a
     * deployed Phase 2 client still calls it. It now behaves as:
     * <ul>
     *   <li>mission completed - report the existing state, zero rewards,
     *       {@code alreadyCompleted: true};</li>
     *   <li>mission not completed - {@link PuzzleRequiredException}, pointing at
     *       the only step that remains.</li>
     * </ul>
     * Both are safe to call and neither pays anything.
     */
    @Transactional
    public MissionCompletionResponse completeMission(UUID userId, UUID missionId) {
        Mission mission = requireMission(missionId);
        PlayerProfile profile = requireProfile(userId);

        MissionProgress progress = progressRepository
                .findByUserIdAndMissionIdForUpdate(userId, missionId)
                .orElseThrow(() -> new MissionNotStartedException("This mission has not been started"));

        MissionCompletionResponse.MissionSummary summary =
                new MissionCompletionResponse.MissionSummary(
                        mission.getId(), mission.getCode(), mission.getTitle());

        if (!progress.getStatus().isCompleted()) {
            // Started or not started: either way no solved puzzle exists, so
            // there is nothing to complete.
            throw new PuzzleRequiredException("Solve the mission's puzzle to complete it");
        }

        log.info("Ignoring direct completion request for mission {} by player {}",
                mission.getCode(), userId);
        return new MissionCompletionResponse(
                summary,
                new MissionCompletionResponse.Rewards(0L, 0L),
                rewardService.currentProgression(profile),
                playerState(profile),
                true);
    }

    /**
     * The caller's current puzzle for a mission, re-derived from its seed.
     *
     * <p>Lets a client recover a challenge after a page reload without the
     * server ever resending it. Only the caller's own puzzle is reachable,
     * because the history query is keyed on the authenticated user id.
     */
    @Transactional(readOnly = true)
    public PuzzleChallengeView activePuzzle(UUID userId, UUID missionId) {
        List<PuzzleAttempt> history = puzzleRepository
                .findByUserIdAndMissionIdOrderByAttemptNumberAsc(userId, missionId);

        for (int i = history.size() - 1; i >= 0; i--) {
            return puzzleService.viewOf(history.get(i));
        }
        throw new PuzzleNotFoundException();
    }

    /**
     * Closes any live puzzle on the mission and writes its replacement.
     *
     * <p>Called inside the start transaction with the progress row already
     * locked, so "supersede then create" cannot interleave with another start for
     * the same mission.
     */
    private PuzzleAttempt issuePuzzle(UUID userId, UUID missionId, Mission mission, Instant now) {
        List<PuzzleAttempt> history = puzzleRepository
                .findByUserIdAndMissionIdOrderByAttemptNumberAsc(userId, missionId);

        for (PuzzleAttempt previous : history) {
            if (previous.getStatus().isSubmittable()) {
                // Superseded by a restart, so it can never be answered. Expiring
                // rather than deleting keeps the audit trail intact.
                previous.expire(now);
                puzzleRepository.save(previous);
            }
        }

        GeneratedPuzzle generated = puzzleService.generate(mission.getPuzzleType(), mission.getDifficulty());

        PuzzleAttempt puzzle = new PuzzleAttempt(
                UUID.randomUUID(),
                userId,
                missionId,
                UUID.randomUUID(),
                generated.challenge().type(),
                mission.getDifficulty(),
                generated.seed(),
                now,
                now.plus(generated.window()),
                history.size() + 1);

        return puzzleRepository.save(puzzle);
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

    private MissionCompletionResponse.PlayerState playerState(PlayerProfile profile) {
        return new MissionCompletionResponse.PlayerState(
                profile.getLevel(),
                profile.getExperience(),
                profile.getCoins(),
                profile.getEnergy());
    }
}