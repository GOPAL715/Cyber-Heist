package com.cyberheist.boss;

import com.cyberheist.boss.dto.BossDetail;
import com.cyberheist.boss.dto.BossListItem;
import com.cyberheist.exception.BossNotFoundException;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the boss system: the catalogue, one boss's detail, and whether
 * the caller may start it.
 *
 * <p>Deliberately separate from {@link BossEncounterService}, which owns every
 * mutation. This class takes no locks and writes nothing, so opening the boss
 * board cannot contend with an encounter in progress.
 *
 * <h2>Availability</h2>
 * Four facts decide whether a boss can be started, checked in this order so the
 * player is told the thing they must fix first:
 *
 * <ol>
 *   <li>an encounter is already running — that outranks everything, because the
 *       player cannot start anything while occupied;</li>
 *   <li>below the required level;</li>
 *   <li>a cooldown from a previous attempt is still running;</li>
 *   <li>otherwise available.</li>
 * </ol>
 *
 * Energy is deliberately not checked here. Insufficient energy is worth knowing
 * before committing, so it is reported as its own flag rather than folded into
 * availability — a boss can be {@code AVAILABLE} to a player who simply needs
 * to wait for regeneration.
 */
@Service
public class BossCatalogueService {

    /**
     * Bound on any history scan from the read side.
     *
     * <p>A detail page must never walk an unbounded table, and the number of
     * defeats is a display nicety rather than a statistic worth querying for.
     */
    static final org.springframework.data.domain.Pageable HISTORY_PAGE =
            org.springframework.data.domain.Pageable.ofSize(200);

    private final BossRepository bosses;
    private final BossStageRepository stages;
    private final BossEncounterRepository encounters;
    private final PlayerProfileRepository profiles;
    private final Clock clock;

    /**
     * Spring entry point. The {@code Clock} overload exists for tests and is
     * deliberately not annotated, so Spring always takes this one.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public BossCatalogueService(BossRepository bosses,
                                BossStageRepository stages,
                                BossEncounterRepository encounters,
                                PlayerProfileRepository profiles) {
        this(bosses, stages, encounters, profiles, Clock.systemUTC());
    }

    BossCatalogueService(BossRepository bosses,
                         BossStageRepository stages,
                         BossEncounterRepository encounters,
                         PlayerProfileRepository profiles,
                         Clock clock) {
        this.bosses = bosses;
        this.stages = stages;
        this.encounters = encounters;
        this.profiles = profiles;
        this.clock = clock;
    }

    /** Every active boss, cheapest level gate first, with the caller's state. */
    @Transactional(readOnly = true)
    public List<BossListItem> catalogue(UUID userId) {
        Instant now = clock.instant();
        List<Boss> active = bosses.findByActiveTrueOrderByRequiredLevelAscCodeAsc();
        if (active.isEmpty()) {
            return List.of();
        }

        int level = currentLevel(userId);
        Optional<BossEncounter> activeEncounter = encounters.findByUserIdAndStatus(
                userId, EncounterStatus.ACTIVE);

        Map<UUID, List<BossStage>> stagesByBoss = new HashMap<>();
        for (BossStage stage : stages.findByBossIdInOrderByBossIdAscStageNumberAsc(
                active.stream().map(Boss::getId).toList())) {
            stagesByBoss.computeIfAbsent(stage.getBossId(), key -> new java.util.ArrayList<>()).add(stage);
        }

        return active.stream()
                .map(boss -> toItem(boss, userId, level, activeEncounter, now,
                        stagesByBoss.getOrDefault(boss.getId(), List.of())))
                .toList();
    }

    /** One boss with its phases and the caller's history of it. */
    @Transactional(readOnly = true)
    public BossDetail detail(UUID userId, UUID bossId) {
        Instant now = clock.instant();
        Boss boss = bosses.findById(bossId).orElseThrow(BossNotFoundException::new);
        if (!boss.isActive()) {
            // Reported as missing rather than unavailable: a retired boss should
            // not look like content that is merely unavailable.
            throw new BossNotFoundException();
        }

        int level = currentLevel(userId);
        Optional<BossEncounter> activeEncounter = encounters.findByUserIdAndStatus(
                userId, EncounterStatus.ACTIVE);

        BossListItem item = toItem(boss, userId, level, activeEncounter, now,
                stages.findByBossIdOrderByStageNumberAsc(bossId));

        // Counted over a bounded slice of this player's history: a detail page
        // must never scan an unbounded table.
        int defeats = (int) encounters
                .findByUserIdOrderByCreatedAtDesc(userId, HISTORY_PAGE)
                .stream()
                .filter(row -> row.getBossId().equals(bossId))
                .filter(row -> row.getStatus() != EncounterStatus.VICTORY)
                .count();

        return new BossDetail(
                boss.getId(),
                boss.getCode(),
                boss.getName(),
                boss.getDescription(),
                boss.getDifficulty().name(),
                boss.getRequiredLevel(),
                boss.getEnergyCost(),
                boss.getStageCount(),
                boss.getXpReward(),
                boss.getCoinReward(),
                boss.getCooldownVictoryMinutes(),
                boss.getCooldownDefeatMinutes(),
                item.availability(),
                item.canStart(),
                item.blockedReason(),
                item.cooldownUntil(),
                item.stages(),
                defeats);
    }

    private BossListItem toItem(Boss boss,
                                UUID userId,
                                int level,
                                Optional<BossEncounter> activeEncounter,
                                Instant now,
                                List<BossStage> bossStages) {

        BossAvailability availability;
        String reason = null;
        Instant cooldownUntil = null;
        UUID activeId = null;

        boolean occupied = activeEncounter.isPresent();
        if (occupied) {
            activeId = activeEncounter.get().getId();
        }

        // The cooldown is the latest one still running, so a defeat followed by
        // a victory cannot leave a stale earlier cooldown in charge.
        Instant cooldown = encounters.findCooldowns(userId, boss.getId(), now).stream()
                .map(BossEncounter::getCooldownUntil)
                .max(Instant::compareTo)
                .orElse(null);
        cooldownUntil = cooldown;

        if (occupied) {
            availability = BossAvailability.ACTIVE;
            reason = "You are already in a boss encounter";
        } else if (level < boss.getRequiredLevel()) {
            availability = BossAvailability.LOCKED;
            reason = "Requires level " + boss.getRequiredLevel();
        } else if (cooldown != null) {
            availability = BossAvailability.COOLDOWN;
            reason = "Available again at " + cooldown;
        } else {
            availability = BossAvailability.AVAILABLE;
        }

        return new BossListItem(
                boss.getId(),
                boss.getCode(),
                boss.getName(),
                boss.getDescription(),
                boss.getDifficulty().name(),
                boss.getRequiredLevel(),
                boss.getEnergyCost(),
                boss.getStageCount(),
                boss.getXpReward(),
                boss.getCoinReward(),
                availability.name(),
                availability == BossAvailability.AVAILABLE,
                reason,
                cooldownUntil,
                activeId,
                bossStages.stream().map(BossListItem.StageView::of).toList());
    }

    private int currentLevel(UUID userId) {
        return profiles.findByUserId(userId).map(PlayerProfile::getLevel).orElse(1);
    }
}