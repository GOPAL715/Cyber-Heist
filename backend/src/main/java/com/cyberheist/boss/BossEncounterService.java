package com.cyberheist.boss;

import com.cyberheist.boss.dto.BossStageSubmission;
import com.cyberheist.boss.dto.EncounterState;
import com.cyberheist.bonus.PlayerBonusService;
import com.cyberheist.energy.EnergyService;
import com.cyberheist.game.PlayerMilestoneService;
import com.cyberheist.exception.BossNotFoundException;
import com.cyberheist.exception.BossPuzzleNotFoundException;
import com.cyberheist.exception.BossUnavailableException;
import com.cyberheist.exception.NoActiveEncounterException;
import com.cyberheist.puzzle.GeneratedPuzzle;
import com.cyberheist.puzzle.PuzzleAttempt;
import com.cyberheist.puzzle.PuzzleAttemptRepository;
import com.cyberheist.puzzle.PuzzleAttemptStatus;
import com.cyberheist.puzzle.PuzzleService;
import com.cyberheist.puzzle.dto.PuzzleChallengeView;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.reward.Reward;
import com.cyberheist.reward.RewardService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Boss encounters: start one, play it through, end it.
 *
 * <p>A boss is deliberately not a mission. It has its own state machine, its own
 * puzzle attempts, a one-shot entry cost, and a consequence for losing. What it
 * does <em>not</em> have is its own puzzle generation or its own reward
 * arithmetic: generation stays inside {@code PuzzleService} and payouts flow
 * through {@code RewardService}, so a boss fight is the existing systems
 * composed rather than a parallel implementation of them.
 *
 * <h2>Lock ordering</h2>
 * Every mutating path takes locks in one fixed order — profile, then encounter,
 * then puzzle — matching the rest of the application. That is what stops a boss
 * request and a mission request from deadlocking against each other.
 *
 * <h2>Failure is cheap to state and expensive to trigger</h2>
 * One wrong answer ends the encounter, pays nothing, and refunds nothing. That
 * is a deliberate Phase 6 rule: a boss should be a commitment, and a player who
 * misreads a cipher should feel it. The entry energy is the price of admission.
 */
@Service
public class BossEncounterService {

    private static final Logger log = LoggerFactory.getLogger(BossEncounterService.class);

    /**
     * How long a whole encounter may last before it lapses.
     *
     * <p>Long enough for a careful player to finish three phases with time to
     * think, short enough that an abandoned tab does not hold a boss hostage.
     * Resolved lazily from the timestamp; there is no background job.
     */
    static final Duration ENCOUNTER_WINDOW = Duration.ofMinutes(60);

    /** Hard cap on the history endpoint, per the Phase 6 requirement. */
    static final int HISTORY_LIMIT = 20;

    private final BossRepository bosses;
    private final BossStageRepository stages;
    private final BossEncounterRepository encounters;
    private final PuzzleAttemptRepository puzzles;
    private final PuzzleService puzzleService;
    private final RewardService rewardService;
    private final PlayerBonusService bonusService;
    private final EnergyService energyService;
    private final PlayerMilestoneService milestoneService;
    private final Clock clock;

    @Autowired
    public BossEncounterService(BossRepository bosses,
                               BossStageRepository stages,
                               BossEncounterRepository encounters,
                               PuzzleAttemptRepository puzzles,
                               PuzzleService puzzleService,
                               RewardService rewardService,
                               PlayerBonusService bonusService,
                               EnergyService energyService,
                               PlayerMilestoneService milestoneService) {
        this(bosses, stages, encounters, puzzles, puzzleService, rewardService,
                bonusService, energyService, milestoneService, Clock.systemUTC());
    }

    BossEncounterService(BossRepository bosses,
                         BossStageRepository stages,
                         BossEncounterRepository encounters,
                         PuzzleAttemptRepository puzzles,
                         PuzzleService puzzleService,
                         RewardService rewardService,
                         PlayerBonusService bonusService,
                         EnergyService energyService,
                         PlayerMilestoneService milestoneService,
                         Clock clock) {
        this.bosses = bosses;
        this.stages = stages;
        this.encounters = encounters;
        this.puzzles = puzzles;
        this.puzzleService = puzzleService;
        this.rewardService = rewardService;
        this.bonusService = bonusService;
        this.energyService = energyService;
        this.milestoneService = milestoneService;
        this.clock = clock;
    }

    // =====================================================================
    // Starting
    // =====================================================================

    /**
     * Charges the entry cost, opens an encounter and issues the first phase.
     *
     * <p>All three happen in one transaction, so a player is never charged for a
     * fight that did not open, and never handed a fight they did not pay for.
     * Every rejection happens before the first write.
     *
     * <p>Energy is charged <em>once</em>, here. Per-phase charging would make a
     * long encounter the most expensive thing in the game and would reward
     * abandoning a fight early, which is the opposite of the intent.
     */
    @Transactional
    public EncounterState start(UUID userId, UUID bossId) {
        Instant now = clock.instant();

        // Lock 1: the profile. Serialises concurrent starts for this player, so
        // two simultaneous requests cannot both pass the "already fighting"
        // check or both spend the balance.
        PlayerProfile profile = energyService.lockAndRefresh(userId);

        Boss boss = bosses.findById(bossId).orElseThrow(BossNotFoundException::new);
        if (!boss.isActive()) {
            throw new BossNotFoundException();
        }

        requireLevel(profile, boss);
        requireNoActiveEncounter(userId, now);
        requireOffCooldown(userId, boss, now);

        // The authoritative charge: the catalogue's cost, reduced by whatever
        // energy efficiency the player's gear and skills are worth, floored at 1.
        int efficiency = bonusService.bonusFor(userId, com.cyberheist.shop.ItemEffectType.ENERGY_EFFICIENCY);
        int cost = com.cyberheist.shop.EquipmentBonusService.applyEnergyDiscount(boss.getEnergyCost(), efficiency);

        if (profile.getEnergy() < cost) {
            throw new BossUnavailableException(
                    "Not enough energy: this boss costs %d, you have %d".formatted(cost, profile.getEnergy()));
        }
        profile.spendEnergy(cost);

        BossEncounter encounter = encounters.save(new BossEncounter(
                UUID.randomUUID(), userId, boss.getId(), boss.getStageCount(), now, ENCOUNTER_WINDOW));

        // The first phase's puzzle is part of the same transaction: an encounter
        // that exists without a puzzle would strand the player.
        PuzzleAttempt firstPuzzle = issueStagePuzzle(encounter, boss, 1, now);
        puzzles.save(firstPuzzle);

        log.info("Player {} entered boss {} for {} energy (stage 1 of {})",
                userId, boss.getCode(), cost, boss.getStageCount());

        return describe(encounter, boss, firstPuzzle,
                "You are through the door. It knows you are here.");
    }

    // =====================================================================
    // Playing
    // =====================================================================

    /**
     * Answers the live phase's puzzle and advances, or ends the encounter.
     *
     * <p>The order is the security order, and every step depends on the one
     * before it: lock the encounter, prove the puzzle belongs to it and to this
     * stage, check it is still answerable, then judge the answer. Only after the
     * verdict does anything change.
     *
     * <p>The whole thing is one transaction, so a failure at any point rolls back
     * the puzzle consumption along with everything else.
     */
    @Transactional
    public EncounterState submitStage(UUID userId, BossStageSubmission submission) {
        Instant now = clock.instant();

        // Lock 1: the profile, for the same lock order as every other path. Kept
        // because a victory writes to it and it must be the same locked row.
        PlayerProfile profile = energyService.lockAndRefresh(userId);

        // Lock 2: the encounter. Two concurrent submissions serialise here, so
        // the second finds the puzzle already consumed or the encounter already
        // terminal, and pays nothing.
        BossEncounter encounter = encounters.findActiveForUpdate(userId).orElseThrow(NoActiveEncounterException::new);

        // An encounter nobody looks at lapses on first contact rather than on a
        // timer. Checked before the puzzle so an expired fight cannot be won.
        if (encounter.isExpiredAt(now)) {
            return lapse(encounter, now);
        }

        Boss boss = bosses.findById(encounter.getBossId()).orElseThrow(BossNotFoundException::new);

        // Lock 3: the puzzle itself.
        PuzzleAttempt puzzle = puzzles.findByPuzzleIdForUpdate(submission.puzzleId())
                .orElseThrow(BossPuzzleNotFoundException::new);

        // Ownership, encounter binding and stage binding in one check. Another
        // player's puzzle, another encounter's puzzle, and a mission puzzle are
        // all reported identically.
        if (!puzzle.getUserId().equals(userId)
                || !encounter.getId().equals(puzzle.getBossEncounterId())
                || puzzle.getAttemptNumber() != encounter.getCurrentStage()) {
            log.warn("Player {} tried to submit puzzle {} outside encounter {} stage {}",
                    userId, submission.puzzleId(), encounter.getId(), encounter.getCurrentStage());
            throw new BossPuzzleNotFoundException();
        }

        if (!puzzle.getStatus().isSubmittable()) {
            // Already answered. A repeated final submission lands here, which is
            // why a replay cannot pay twice.
            return describe(encounter, boss, null,
                    "That phase has already been answered.");
        }

        // Expiry is judged before the answer is looked at, so a late answer is
        // refused whether it was right or wrong.
        if (puzzle.isExpiredAt(now)) {
            puzzles.expireOpenBossPuzzles(encounter.getId(), now);
            encounter.lose(now);
            applyCooldown(encounter, boss, EncounterStatus.DEFEATED, now);
            encounters.save(encounter);
            log.info("Player {} missed the window on stage {} of boss {}",
                    userId, encounter.getCurrentStage(), boss.getCode());
            return describe(encounter, boss, null,
                    "The window closed. The connection dropped and it kept everything.");
        }

        boolean correct = puzzleService.isCorrect(
                puzzle.getPuzzleType(), puzzle.getDifficulty(), puzzle.getSeed(), submission.answer());

        if (!correct) {
            // One wrong answer ends it. No reward, no refund.
            puzzle.submit(PuzzleAttemptStatus.FAILED, now);
            puzzles.save(puzzle);
            puzzles.expireOpenBossPuzzles(encounter.getId(), now);
            encounter.lose(now);
            applyCooldown(encounter, boss, EncounterStatus.DEFEATED, now);
            encounters.save(encounter);
            log.info("Player {} failed stage {} of boss {}; no rewards",
                    userId, encounter.getCurrentStage(), boss.getCode());
            return describe(encounter, boss, null,
                    "ACCESS DENIED. The boss read your intent and closed the door.");
        }

        puzzle.submit(PuzzleAttemptStatus.SUCCEEDED, now);
        puzzles.save(puzzle);

        // The damage is read from boss_stages. Nothing about it came from the
        // request: there is no damage field in the submission DTO.
        BossStage cleared = stages.findByBossIdAndStageNumber(boss.getId(), encounter.getCurrentStage())
                .orElseThrow(() -> new BossUnavailableException("This boss has no such stage"));

        boolean finalStage = encounter.getCurrentStage() >= boss.getStageCount();

        if (finalStage) {
            return win(encounter, boss, cleared, profile, now);
        }

        int nextStage = encounter.getCurrentStage() + 1;
        encounter.damage(cleared.getDamageValue(), nextStage);
        encounters.save(encounter);

        // The next phase's puzzle is issued in the same transaction as the stage
        // being cleared, so the response always has something to answer.
        PuzzleAttempt nextPuzzle = issueStagePuzzle(encounter, boss, nextStage, now);
        puzzles.save(nextPuzzle);

        log.info("Player {} cleared stage {} of boss {} (integrity {}), stage {} issued",
                userId, cleared.getStageNumber(), boss.getCode(), encounter.getBossIntegrity(), nextStage);

        return describe(encounter, boss, nextPuzzle,
                "STAGE %d CLEARED. Integrity %d. NEXT PHASE."
                        .formatted(cleared.getStageNumber(), encounter.getBossIntegrity()));
    }

    /**
     * Ends the encounter in victory and pays.
     *
     * <p>The reward path is the ordinary one: the catalogue's base amounts are
     * modified by the player's capped equipment and skill bonuses, then granted
     * through {@code RewardService}, which is what also awards the level-up and
     * the skill point that comes with it. No boss-specific reward arithmetic
     * exists.
     */
    private EncounterState win(BossEncounter encounter, Boss boss, BossStage cleared,
                               PlayerProfile profile, Instant now) {
        Reward base = new Reward(boss.getXpReward(), boss.getCoinReward());
        Reward finalReward = RewardService.applyBonuses(base, bonusService.bonusesFor(encounter.getUserId()));

        // `profile` is the row the caller already locked and refreshed, so the
        // reward lands on the current balance and the caller's transaction
        // commits it with everything else.
        //
        // The level is sampled before and after the whole method rather than taken
        // from the boss reward's own ProgressionResult, because a Phase 7 milestone
        // unlocked by this very win can add XP and cross a level boundary too.
        // Reporting only the boss's contribution would understate what the player
        // actually gained from defeating it.
        int levelBefore = profile.getLevel();
        int skillPointsBefore = profile.getSkillPoints();
        rewardService.grant(profile, finalReward);

        encounter.damage(cleared.getDamageValue(), 0);
        encounter.win(finalReward.experience(), finalReward.coins(), now);
        applyCooldown(encounter, boss, EncounterStatus.VICTORY, now);
        encounters.save(encounter);

        log.info("Player {} defeated boss {} (+{} xp, +{} coins, level {} -> {})",
                encounter.getUserId(), boss.getCode(), finalReward.experience(),
                finalReward.coins(), levelBefore, profile.getLevel());

        // Phase 7. Only the victory path reports anything: a defeat or an expiry
        // earns no milestone, so there is deliberately no call on those branches.
        // Runs inside the caller's transaction with the already-locked profile, so a
        // milestone unlock and its payout commit with the win that caused them.
        milestoneService.bossDefeated(encounter.getUserId(), profile,
                finalReward.experience(), finalReward.coins());

        int levelAfter = profile.getLevel();
        EncounterState state = describe(encounter, boss, null,
                "BOSS DEFEATED. " + boss.getName() + " is down.");
        return new EncounterState(
                state.encounterId(), state.bossId(), state.bossCode(), state.bossName(),
                state.bossDifficulty(), state.status(), state.currentStage(), state.stageCount(),
                state.bossIntegrity(), state.bossIntegrityPercent(), state.reachedStage(),
                state.puzzle(), state.stageName(), state.stageDescription(),
                state.xpAwarded(), state.coinAwarded(), state.startedAt(), state.expiresAt(),
                state.cooldownUntil(), state.outcomeMessage(),
                new EncounterState.Rewards(finalReward.experience(), finalReward.coins()),
                new EncounterState.Progression(levelBefore, levelAfter,
                        Math.max(0, levelAfter - levelBefore),
                        profile.getSkillPoints() - skillPointsBefore));
    }

    // =====================================================================
    // Reading
    // =====================================================================

    /** The caller's live encounter, or 404 when there is none. */
    @Transactional
    public EncounterState current(UUID userId) {
        Instant now = clock.instant();
        BossEncounter encounter = encounters.findByUserIdAndStatus(userId, EncounterStatus.ACTIVE)
                .orElseThrow(NoActiveEncounterException::new);

        if (encounter.isExpiredAt(now)) {
            Boss boss = bosses.findById(encounter.getBossId()).orElseThrow(BossNotFoundException::new);
            return lapse(encounter, boss, now);
        }

        Boss boss = bosses.findById(encounter.getBossId()).orElseThrow(BossNotFoundException::new);
        PuzzleAttempt puzzle = puzzles.findByBossEncounterIdAndAttemptNumber(
                encounter.getId(), encounter.getCurrentStage()).orElse(null);
        return describe(encounter, boss, puzzle, null);
    }

    /** The caller's recent encounters, newest first and hard-bounded. */
    @Transactional(readOnly = true)
    public List<EncounterState> history(UUID userId) {
        return encounters.findByUserIdOrderByCreatedAtDesc(userId, Pageable.ofSize(HISTORY_LIMIT)).stream()
                .map(row -> {
                    // bosses is never deleted from under an encounter - the
                    // foreign key is ON DELETE RESTRICT - so this lookup cannot
                    // miss. Failing loudly is better than rendering a blank row.
                    Boss boss = bosses.findById(row.getBossId()).orElseThrow(BossNotFoundException::new);
                    return describeHistory(row, boss);
                })
                .toList();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Generates and stores the puzzle for one phase.
     *
     * <p>Generation is {@code PuzzleService}'s job: this only decides which type
     * and difficulty the stage asked for, and overrides the window with the
     * stage's own budget so a boss phase is predictable regardless of family.
     * The seed is what gets persisted; the answer is not.
     */
    private PuzzleAttempt issueStagePuzzle(BossEncounter encounter, Boss boss, int stageNumber, Instant now) {
        BossStage stage = stages.findByBossIdAndStageNumber(boss.getId(), stageNumber)
                .orElseThrow(() -> new BossUnavailableException("This boss has no stage " + stageNumber));

        GeneratedPuzzle generated = puzzleService.generate(stage.getPuzzleType(), stage.getDifficulty());

        return puzzles.save(PuzzleAttempt.forBossStage(
                UUID.randomUUID(),
                encounter.getUserId(),
                encounter.getId(),
                UUID.randomUUID(),
                generated.challenge().type(),
                stage.getDifficulty(),
                generated.seed(),
                now,
                now.plusSeconds(stage.getTimeLimitSeconds()),
                stageNumber));
    }

    /** Resolves an elapsed encounter as EXPIRED and applies its cooldown. */
    private EncounterState lapse(BossEncounter encounter, Instant now) {
        Boss boss = bosses.findById(encounter.getBossId()).orElseThrow(BossNotFoundException::new);
        return lapse(encounter, boss, now);
    }

    private EncounterState lapse(BossEncounter encounter, Boss boss, Instant now) {
        puzzles.expireOpenBossPuzzles(encounter.getId(), now);
        encounter.expire(now);
        applyCooldown(encounter, boss, EncounterStatus.EXPIRED, now);
        encounters.save(encounter);
        log.info("Encounter {} for player {} lapsed after its window closed",
                encounter.getId(), encounter.getUserId());
        return describe(encounter, boss, null,
                "The connection went cold. " + boss.getName() + " is still waiting.");
    }

    /**
     * Sets the cooldown from the boss's own configuration.
     *
     * <p>Computed from the server clock at the moment the encounter ends, so a
     * client cannot shorten it and no scheduled job is needed.
     */
    private void applyCooldown(BossEncounter encounter, Boss boss, EncounterStatus outcome, Instant now) {
        encounter.startCooldown(now.plus(Duration.ofMinutes(boss.cooldownMinutesFor(outcome))));
    }

    private void requireLevel(PlayerProfile profile, Boss boss) {
        if (profile.getLevel() < boss.getRequiredLevel()) {
            throw new BossUnavailableException("Requires level " + boss.getRequiredLevel());
        }
    }

    private void requireNoActiveEncounter(UUID userId, Instant now) {
        Optional<BossEncounter> existing = encounters.findByUserIdAndStatus(userId, EncounterStatus.ACTIVE);
        if (existing.isPresent()) {
            // Also the natural place to resolve a stale encounter: if the one they
            // are holding has lapsed, it stops blocking them here rather than
            // needing a cleanup job.
            if (existing.get().isExpiredAt(now)) {
                Boss boss = bosses.findById(existing.get().getBossId()).orElse(null);
                if (boss != null) {
                    lapse(existing.get(), boss, now);
                    return;
                }
            }
            throw new BossUnavailableException("You are already in a boss encounter");
        }
    }

    private void requireOffCooldown(UUID userId, Boss boss, Instant now) {
        boolean cooling = encounters.findCooldowns(userId, boss.getId(), now).stream()
                .findAny()
                .isPresent();
        if (cooling) {
            Instant until = encounters.findCooldowns(userId, boss.getId(), now).stream()
                    .map(BossEncounter::getCooldownUntil)
                    .max(Instant::compareTo)
                    .orElse(now);
            throw new BossUnavailableException("This boss is on cooldown until " + until);
        }
    }

    /**
     * Projects an encounter for the API.
     *
     * <p>The challenge is attached only while a phase is live. A terminal
     * encounter reports what it paid and when the boss may be fought again, and
     * no puzzle — there is nothing left to answer.
     */
    private EncounterState describe(BossEncounter encounter,
                                    Boss boss,
                                    PuzzleAttempt puzzle,
                                    String message) {
        BossStage stage = puzzle == null
                ? stages.findByBossIdAndStageNumber(boss.getId(),
                        Math.min(encounter.getCurrentStage(), boss.getStageCount())).orElse(null)
                : stages.findByBossIdAndStageNumber(boss.getId(), puzzle.getAttemptNumber()).orElse(null);

        PuzzleChallengeView challenge = puzzle == null ? null : puzzleService.viewOf(puzzle);

        EncounterState.Rewards rewards = encounter.getStatus() == EncounterStatus.VICTORY
                ? new EncounterState.Rewards(encounter.getXpAwarded(), encounter.getCoinAwarded())
                : null;

        return new EncounterState(
                encounter.getId(),
                boss.getId(),
                boss.getCode(),
                boss.getName(),
                boss.getDifficulty().name(),
                encounter.getStatus(),
                encounter.getCurrentStage(),
                boss.getStageCount(),
                encounter.getBossIntegrity(),
                encounter.integrityPercent(),
                encounter.getReachedStage(),
                challenge,
                stage == null ? null : stage.getName(),
                stage == null ? null : stage.getDescription(),
                encounter.getXpAwarded(),
                encounter.getCoinAwarded(),
                encounter.getStartedAt(),
                encounter.getExpiresAt(),
                encounter.getCooldownUntil(),
                message,
                rewards,
                null);
    }

    /** A history row: no challenge, no internal detail beyond what the player saw. */
    private EncounterState describeHistory(BossEncounter encounter, Boss boss) {
        EncounterState base = describe(encounter,
                boss, null, null);
        return new EncounterState(
                base.encounterId(), base.bossId(), base.bossCode(), base.bossName(),
                base.bossDifficulty(), base.status(), base.currentStage(), base.stageCount(),
                base.bossIntegrity(), base.bossIntegrityPercent(), base.reachedStage(),
                null, null, null,
                base.xpAwarded(), base.coinAwarded(), base.startedAt(), base.expiresAt(),
                base.cooldownUntil(), null, base.rewards(), null);
    }
}
