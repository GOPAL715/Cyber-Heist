package com.cyberheist.achievement;

import com.cyberheist.boss.BossEncounterRepository;
import com.cyberheist.boss.EncounterStatus;
import com.cyberheist.daily.DailyChallengeProgressRepository;
import com.cyberheist.daily.PlayerStreak;
import com.cyberheist.daily.PlayerStreakRepository;
import com.cyberheist.mission.MissionProgressRepository;
import com.cyberheist.mission.MissionStatus;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.puzzle.PuzzleAttemptRepository;
import com.cyberheist.puzzle.PuzzleType;
import com.cyberheist.shop.PlayerEquipmentRepository;
import com.cyberheist.shop.PlayerInventoryRepository;
import com.cyberheist.skill.PlayerSkillRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measures a player against the milestone catalogue.
 *
 * <p>Every number this produces is read from a table that already records real
 * play. Nothing is tallied here and nothing is stored between requests, so the
 * central property of the achievement system falls out for free: a replayed
 * submission or a second concurrent completion re-reads the same facts and reaches
 * the same conclusion, instead of adding a delta a second time.
 *
 * <p>Two consequences worth stating, because they are why this is derived rather
 * than accumulated:
 * <ul>
 *   <li>a player who already satisfies a milestone before this feature exists is
 *       credited the first time their state is evaluated, rather than being locked
 *       out of it forever;</li>
 *   <li>a milestone can never be inflated by replaying the request that earned it.</li>
 * </ul>
 *
 * <p>Only the requirement types an active achievement actually uses are measured,
 * so the query count follows the catalogue rather than the enum.
 */
@Service
public class PlayerStatsService {

    private final MissionProgressRepository missions;
    private final PuzzleAttemptRepository puzzles;
    private final PlayerInventoryRepository inventory;
    private final PlayerEquipmentRepository equipment;
    private final PlayerSkillRepository skills;
    private final BossEncounterRepository encounters;
    private final DailyChallengeProgressRepository dailyProgress;
    private final PlayerStreakRepository streaks;

    public PlayerStatsService(MissionProgressRepository missions,
                              PuzzleAttemptRepository puzzles,
                              PlayerInventoryRepository inventory,
                              PlayerEquipmentRepository equipment,
                              PlayerSkillRepository skills,
                              BossEncounterRepository encounters,
                              DailyChallengeProgressRepository dailyProgress,
                              PlayerStreakRepository streaks) {
        this.missions = missions;
        this.puzzles = puzzles;
        this.inventory = inventory;
        this.equipment = equipment;
        this.skills = skills;
        this.encounters = encounters;
        this.dailyProgress = dailyProgress;
        this.streaks = streaks;
    }

    /**
     * Measures exactly the requirements asked for.
     *
     * @param userId  whose state to read
     * @param needed  the requirement types the caller will consult; anything else is
     *                left unmeasured and reads as zero
     * @param profile the caller's profile, so level and lifetime coins are read from
     *                the in-memory instance rather than re-queried
     */
    @Transactional(readOnly = true)
    public PlayerStats measure(UUID userId, Set<AchievementRequirement> needed, PlayerProfile profile) {
        PlayerStats.Builder builder = PlayerStats.builder();

        // Two of these come straight off the profile, so they cost nothing.
        if (needed.contains(AchievementRequirement.PLAYER_LEVEL)) {
            builder.measure(AchievementRequirement.PLAYER_LEVEL, profile.getLevel());
        }
        if (needed.contains(AchievementRequirement.COINS_EARNED)) {
            builder.measure(AchievementRequirement.COINS_EARNED, profile.getCoinsEarned());
        }

        if (needed.contains(AchievementRequirement.MISSIONS_COMPLETED)) {
            builder.measure(AchievementRequirement.MISSIONS_COMPLETED,
                    missions.countByUserIdAndStatus(userId, MissionStatus.COMPLETED));
        }
        if (needed.contains(AchievementRequirement.PUZZLES_SOLVED)) {
            builder.measure(AchievementRequirement.PUZZLES_SOLVED,
                    puzzles.countSolvedMissionPuzzles(userId));
        }

        // The five puzzle families are one query each, issued only if a milestone
        // asks for that family.
        measurePuzzleFamily(builder, userId, needed, AchievementRequirement.CIPHER_SOLVED, PuzzleType.CIPHER);
        measurePuzzleFamily(builder, userId, needed, AchievementRequirement.LOGIC_SOLVED, PuzzleType.LOGIC);
        measurePuzzleFamily(builder, userId, needed, AchievementRequirement.PATTERN_SOLVED, PuzzleType.PATTERN);
        measurePuzzleFamily(builder, userId, needed, AchievementRequirement.SEQUENCE_SOLVED, PuzzleType.SEQUENCE);
        measurePuzzleFamily(builder, userId, needed, AchievementRequirement.TIMED_SOLVED, PuzzleType.TIMED);

        if (needed.contains(AchievementRequirement.ITEMS_OWNED)) {
            builder.measure(AchievementRequirement.ITEMS_OWNED, inventory.countByUserId(userId));
        }
        if (needed.contains(AchievementRequirement.ITEMS_EQUIPPED)) {
            builder.measure(AchievementRequirement.ITEMS_EQUIPPED, equipment.countByUserId(userId));
        }
        if (needed.contains(AchievementRequirement.SKILLS_UNLOCKED)) {
            builder.measure(AchievementRequirement.SKILLS_UNLOCKED, skills.countByUserId(userId));
        }
        if (needed.contains(AchievementRequirement.SKILL_LEVEL)) {
            Integer highest = skills.findHighestSkillLevel(userId);
            builder.measure(AchievementRequirement.SKILL_LEVEL, highest == null ? 0L : highest);
        }
        if (needed.contains(AchievementRequirement.BOSSES_DEFEATED)) {
            builder.measure(AchievementRequirement.BOSSES_DEFEATED,
                    encounters.countByUserIdAndStatus(userId, EncounterStatus.VICTORY));
        }
        if (needed.contains(AchievementRequirement.DAILY_CHALLENGES_COMPLETED)) {
            builder.measure(AchievementRequirement.DAILY_CHALLENGES_COMPLETED,
                    dailyProgress.countByUserIdAndCompletedTrue(userId));
        }
        if (needed.contains(AchievementRequirement.LOGIN_STREAK)) {
            builder.measure(AchievementRequirement.LOGIN_STREAK, currentStreak(userId));
        }

        return builder.build();
    }

    /**
     * The current streak length, zero for a player who has never been active.
     *
     * <p>Read from the streak row rather than recomputed, so a player who has not
     * played today still sees the streak they have rather than a broken one - the
     * reset belongs to their next activity, not to a read.
     */
    @Transactional(readOnly = true)
    public long currentStreak(UUID userId) {
        return streaks.findByUserId(userId)
                .map(row -> (long) row.getCurrentStreak())
                .orElse(0L);
    }

    /**
     * Every requirement type referenced by a catalogue.
     *
     * <p>Derived from the rows themselves, so adding an achievement that uses a new
     * requirement automatically causes that requirement to be measured.
     */
    public Set<AchievementRequirement> requirementsOf(Iterable<Achievement> catalogue) {
        Set<AchievementRequirement> needed = EnumSet.noneOf(AchievementRequirement.class);
        for (Achievement achievement : catalogue) {
            needed.add(achievement.getRequirement());
        }
        return needed;
    }

    private void measurePuzzleFamily(PlayerStats.Builder builder, UUID userId,
                                     Set<AchievementRequirement> needed,
                                     AchievementRequirement requirement, PuzzleType type) {
        if (!needed.contains(requirement)) {
            return;
        }
        builder.measure(requirement, puzzles.countSolvedMissionPuzzlesOfType(userId, type));
    }

    /** Exposed for the daily system, which counts completions in a date window. */
    @Transactional(readOnly = true)
    public long dailyCompletionsSince(UUID userId, Instant since) {
        return dailyProgress.findCompletedSince(userId, since).size();
    }
}
