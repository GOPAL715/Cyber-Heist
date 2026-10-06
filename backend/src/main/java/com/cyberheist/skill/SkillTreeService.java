package com.cyberheist.skill;

import com.cyberheist.bonus.PlayerBonusService;
import com.cyberheist.exception.SkillNotFoundException;
import com.cyberheist.exception.SkillUnavailableException;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import com.cyberheist.skill.dto.SkillTreeResponse;
import com.cyberheist.skill.dto.SkillUnlockResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The skill tree: what exists, what a player may take next, and taking it.
 *
 * <p><strong>One place for the rules.</strong> Unlock legality, prerequisite
 * checking and point spending all live here. No other service consults a
 * prerequisite or a skill cost, so a rule cannot be enforced in one path and
 * forgotten in another.
 *
 * <h2>Authority</h2>
 * The request says only "take this skill to the next level". There is no cost, no
 * level, no effect and no prerequisite in any request DTO, because every one of
 * those is read from the {@code skill_levels} and {@code skill_prerequisites}
 * tables. A body carrying {@code {"cost": 1, "effectValue": 999999}} has no field
 * to bind to.
 *
 * <h2>Locking</h2>
 * Every unlock takes the player's profile row as a pessimistic write lock first,
 * before reading the balance or writing the skill. That single lock serialises
 * both competing cases: two requests spending the same points, and two requests
 * taking the same level of the same skill. It is also the order the rest of the
 * application uses - profile before anything else - so no request can hold a
 * skill lock while waiting for the profile.
 *
 * <h2>Cycle safety</h2>
 * A cyclic prerequisite graph would make skills permanently unreachable, so it is
 * rejected at startup rather than discovered by a confused player. Self-reference
 * and duplicate edges are already impossible by CHECK and primary key; this
 * catches the case those cannot express.
 */
@Service
public class SkillTreeService {

    private static final Logger log = LoggerFactory.getLogger(SkillTreeService.class);

    private final SkillRepository skills;
    private final SkillLevelRepository levels;
    private final SkillPrerequisiteRepository prerequisites;
    private final PlayerSkillRepository playerSkills;
    private final PlayerProfileRepository profiles;
    private final PlayerBonusService bonusService;

    public SkillTreeService(SkillRepository skills,
                            SkillLevelRepository levels,
                            SkillPrerequisiteRepository prerequisites,
                            PlayerSkillRepository playerSkills,
                            PlayerProfileRepository profiles,
                            PlayerBonusService bonusService) {
        this.skills = skills;
        this.levels = levels;
        this.prerequisites = prerequisites;
        this.playerSkills = playerSkills;
        this.profiles = profiles;
        this.bonusService = bonusService;

        validateGraphAtStartup();
    }

    /**
     * Rejects a prerequisite graph containing a cycle.
     *
     * <p>Fails fast at boot, the same way {@code PuzzleService} refuses to start
     * with an unclaimed puzzle type: a broken catalogue is a deployment fault,
     * not something a player should hit mid-run. The walk is iterative so a deep
     * chain cannot overflow the stack.
     */
    private void validateGraphAtStartup() {
        Map<UUID, List<UUID>> edges = new HashMap<>();
        for (SkillPrerequisite edge : prerequisites.findAll()) {
            edges.computeIfAbsent(edge.getSkillId(), key -> new ArrayList<>())
                    .add(edge.getRequiredSkillId());
        }

        Set<UUID> settled = new HashSet<>();
        Deque<UUID> pending = new ArrayDeque<>(edges.keySet());

        while (!pending.isEmpty()) {
            Set<UUID> onPath = new HashSet<>();
            Deque<UUID> walk = new ArrayDeque<>();
            walk.push(pending.pop());

            while (!walk.isEmpty()) {
                UUID current = walk.pop();
                if (!onPath.add(current)) {
                    throw new IllegalStateException(
                            "Skill prerequisites contain a cycle involving skill " + current);
                }
                for (UUID required : edges.getOrDefault(current, List.of())) {
                    if (onPath.contains(required)) {
                        throw new IllegalStateException(
                                "Skill prerequisites contain a cycle involving skill " + required);
                    }
                    if (!settled.contains(required)) {
                        walk.push(required);
                    }
                }
                settled.add(current);
            }
        }
        log.info("Skill tree validated: {} prerequisite edges, no cycles", prerequisites.count());
    }

    /**
     * The whole tree for one player: every active skill, what the player has
     * done with it, what it would cost next, and why anything is locked.
     *
     * <p>Returns every skill including ones the player cannot touch yet, because
     * seeing what is ahead is the point of a progression screen.
     */
    @Transactional(readOnly = true)
    public SkillTreeResponse tree(UUID userId) {
        List<Skill> active = skills.findByActiveTrueOrderByBranchAscNameAsc();

        Map<UUID, Integer> unlocked = new HashMap<>();
        playerSkills.findByUserId(userId)
                .forEach(row -> unlocked.put(row.getSkillId(), row.getCurrentLevel()));

        Map<UUID, List<SkillLevel>> levelsBySkill = new HashMap<>();
        for (SkillLevel level : levels.findBySkillIdInOrderBySkillIdAscLevelAsc(
                active.stream().map(Skill::getId).toList())) {
            levelsBySkill.computeIfAbsent(level.getSkillId(), key -> new ArrayList<>()).add(level);
        }

        Map<UUID, List<SkillPrerequisite>> edgesBySkill = new HashMap<>();
        for (SkillPrerequisite edge : prerequisites.findByIdSkillIdIn(active.stream().map(Skill::getId).toList())) {
            edgesBySkill.computeIfAbsent(edge.getSkillId(), key -> new ArrayList<>()).add(edge);
        }

        int points = profiles.findByUserId(userId)
                .map(PlayerProfile::getSkillPoints)
                .orElse(0);

        // Accumulated per branch and only then frozen into the response, so the
        // views can be built branch-agnostically instead of the response being
        // mutated while it is being constructed.
        Map<SkillBranch, List<SkillTreeResponse.SkillView>> byBranch = new LinkedHashMap<>();
        for (SkillBranch branch : SkillBranch.values()) {
            byBranch.put(branch, new ArrayList<>());
        }

        for (Skill skill : active) {
            int currentLevel = unlocked.getOrDefault(skill.getId(), 0);
            List<SkillLevel> skillLevels = levelsBySkill.getOrDefault(skill.getId(), List.of());

            // The prerequisite check is the same code the unlock path runs, so
            // the screen can never say "available" for something the server
            // would refuse.
            SkillTreeResponse.LockedReason locked = lockReason(
                    edgesBySkill.getOrDefault(skill.getId(), List.of()), unlocked);

            Integer nextCost = null;
            String nextEffectType = null;
            Integer nextEffectValue = null;
            if (currentLevel < skill.getMaxLevel()) {
                SkillLevel next = skillLevels.stream()
                        .filter(level -> level.getLevel() == currentLevel + 1)
                        .findFirst()
                        .orElse(null);
                if (next != null) {
                    nextCost = next.getSkillPointCost();
                    nextEffectType = next.getEffectType().name();
                    nextEffectValue = next.getEffectValue();
                }
            }

            boolean maxed = currentLevel >= skill.getMaxLevel();
            boolean affordable = nextCost != null && points >= nextCost && locked == null;
            boolean canUnlock = !maxed && affordable;

            String reason = maxed ? "Fully upgraded"
                    : locked != null ? locked.message()
                    : nextCost == null ? "No further levels are defined"
                    : points < nextCost ? "Not enough skill points"
                    : null;

            SkillTreeResponse.SkillView view = new SkillTreeResponse.SkillView(
                    skill.getId(),
                    skill.getCode(),
                    skill.getName(),
                    skill.getDescription(),
                    skill.getBranch(),
                    currentLevel,
                    skill.getMaxLevel(),
                    skillLevels.stream().map(SkillTreeResponse.LevelView::of).toList(),
                    nextCost,
                    nextEffectType,
                    nextEffectValue,
                    canUnlock,
                    locked != null,
                    reason,
                    prerequisitesOf(edgesBySkill.getOrDefault(skill.getId(), List.of()), unlocked));

            byBranch.get(skill.getBranch()).add(view);
        }

        List<SkillTreeResponse.BranchView> branchViews = byBranch.entrySet().stream()
                .map(entry -> new SkillTreeResponse.BranchView(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        // The capped, combined figures are reported alongside the uncapped
        // split, because the screen must show what the engine applies while a
        // player may also want to see which source contributed what.
        List<SkillTreeResponse.BonusView> effective = bonusService.bonusesFor(userId).entrySet().stream()
                .map(entry -> new SkillTreeResponse.BonusView(entry.getKey().name(), entry.getValue()))
                .toList();

        return new SkillTreeResponse(points, branchViews, bonusService.breakdownFor(userId), effective);
    }

    /**
     * Takes the next level of a skill, spending the points its level costs.
     *
     * <p>Every rejection happens before the first write, so a refused unlock
     * costs the player nothing.
     *
     * @throws SkillNotFoundException   no such skill, or it is retired
     * @throws SkillUnavailableException a prerequisite is unmet, the balance is
     *                                   short, or the skill is already maxed
     */
    @Transactional
    public SkillUnlockResponse unlock(UUID userId, UUID skillId) {
        // Lock first: serialises every unlock this player makes.
        PlayerProfile profile = profiles.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new SkillNotFoundException("Player profile not found"));

        Skill skill = skills.findById(skillId).orElseThrow(SkillNotFoundException::new);
        if (!skill.isActive()) {
            throw new SkillNotFoundException("Skill not found");
        }

        PlayerSkill existing = playerSkills.findByUserIdAndSkillIdForUpdate(userId, skillId).orElse(null);
        int currentLevel = existing == null ? 0 : existing.getCurrentLevel();

        if (currentLevel >= skill.getMaxLevel()) {
            throw new SkillUnavailableException(skill.getName() + " is already fully upgraded");
        }

        // The same evaluation the screen shows, run again because a screen is a
        // snapshot and this is the decision.
        SkillTreeResponse.LockedReason locked = lockReason(
                prerequisites.findByIdSkillIdIn(List.of(skillId)), loadUnlocked(userId));
        if (locked != null) {
            throw new SkillUnavailableException(locked.message());
        }

        int targetLevel = currentLevel + 1;
        SkillLevel next = levels.findBySkillIdAndLevel(skillId, targetLevel)
                .orElseThrow(() -> new SkillUnavailableException(
                        "No further levels are defined for " + skill.getName()));

        if (profile.getSkillPoints() < next.getSkillPointCost()) {
            throw new SkillUnavailableException(
                    "Not enough skill points: this costs " + next.getSkillPointCost()
                            + ", you have " + profile.getSkillPoints());
        }

        profile.spendSkillPoints(next.getSkillPointCost());
        profiles.save(profile);

        if (existing == null) {
            // First level taken, so the row is created now. Absence means level 0.
            playerSkills.save(new PlayerSkill(UUID.randomUUID(), userId, skillId, targetLevel));
        } else {
            existing.advanceTo(targetLevel);
            playerSkills.save(existing);
        }

        log.info("Player {} took {} to level {}/{} for {} point(s), {} remaining",
                userId, skill.getCode(), targetLevel, skill.getMaxLevel(),
                next.getSkillPointCost(), profile.getSkillPoints());

        return new SkillUnlockResponse(
                skill.getId(),
                skill.getCode(),
                skill.getName(),
                targetLevel,
                skill.getMaxLevel(),
                next.getSkillPointCost(),
                next.getEffectType().name(),
                next.getEffectValue(),
                profile.getSkillPoints(),
                bonusService.bonusesFor(userId).entrySet().stream()
                        .map(entry -> new SkillUnlockResponse.BonusView(entry.getKey().name(), entry.getValue()))
                        .toList());
    }

    /** The caller's current levels, keyed by skill id. Missing means level 0. */
    private Map<UUID, Integer> loadUnlocked(UUID userId) {
        Map<UUID, Integer> unlocked = new HashMap<>();
        playerSkills.findByUserId(userId)
                .forEach(row -> unlocked.put(row.getSkillId(), row.getCurrentLevel()));
        return unlocked;
    }

    /**
     * Evaluates a skill's prerequisites against what the player has.
     *
     * <p>Returns the first unmet requirement, or {@code null} when the skill is
     * available. Deliberately checks the <em>lowest</em> unmet requirement by
     * sorting, so the player is told the thing they need to do next rather than
     * an arbitrary one of several.
     */
    private SkillTreeResponse.LockedReason lockReason(List<SkillPrerequisite> edges,
                                                      Map<UUID, Integer> unlocked) {
        List<SkillPrerequisite> ordered = new ArrayList<>(edges);
        ordered.sort(Comparator.comparingInt(SkillPrerequisite::getRequiredLevel));

        for (SkillPrerequisite edge : ordered) {
            int have = unlocked.getOrDefault(edge.getRequiredSkillId(), 0);
            if (have < edge.getRequiredLevel()) {
                String requiredName = skills.findById(edge.getRequiredSkillId())
                        .map(Skill::getName)
                        .orElse("another skill");
                return new SkillTreeResponse.LockedReason(
                        requiredName + " level " + edge.getRequiredLevel() + " required",
                        edge.getRequiredSkillId(),
                        edge.getRequiredLevel());
            }
        }
        return null;
    }

    /** Player-safe rendering of a skill's prerequisites, for the screen. */
    private List<SkillTreeResponse.PrerequisiteView> prerequisitesOf(
            List<SkillPrerequisite> edges, Map<UUID, Integer> unlocked) {
        List<SkillTreeResponse.PrerequisiteView> views = new ArrayList<>(edges.size());
        for (SkillPrerequisite edge : edges) {
            Skill required = skills.findById(edge.getRequiredSkillId()).orElse(null);
            if (required == null) {
                continue;
            }
            views.add(new SkillTreeResponse.PrerequisiteView(
                    required.getId(),
                    required.getCode(),
                    required.getName(),
                    edge.getRequiredLevel(),
                    unlocked.getOrDefault(required.getId(), 0)));
        }
        return views;
    }
}