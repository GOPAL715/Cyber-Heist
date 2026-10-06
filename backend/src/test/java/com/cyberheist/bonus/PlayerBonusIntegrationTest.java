package com.cyberheist.bonus;

import static org.assertj.core.api.Assertions.assertThat;

import com.cyberheist.IntegrationTestSupport;
import com.cyberheist.shop.EquipmentBonusService;
import com.cyberheist.shop.ItemEffectType;
import com.cyberheist.skill.SkillRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Equipment and skill bonuses, added together and capped once.
 *
 * <p>The property worth protecting is that skills cannot be used to escape
 * Phase 4's economy ceiling: a player who maxes the skill tree on top of a
 * full loadout must still be held at 50% XP.
 */
class PlayerBonusIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PlayerBonusService playerBonusService;

    @Autowired
    private SkillRepository skillRepository;

    // ------------------------------------------------------------------
    // Combining and capping, with no database involved.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("combined capping")
    class CombinedCap {

        private final PlayerBonusService service = new PlayerBonusService(null, null);

        @Test
        @DisplayName("an empty player has no bonuses at all")
        void emptyIsEmpty() {
            assertThat(service.cap(Map.of(), Map.of())).isEmpty();
        }

        @Test
        @DisplayName("adds equipment and skills of the same type")
        void addsAcrossSources() {
            Map<ItemEffectType, Integer> combined = service.cap(
                    Map.of(ItemEffectType.EXPERIENCE_BONUS, 10),
                    Map.of(ItemEffectType.EXPERIENCE_BONUS, 15));

            assertThat(combined.get(ItemEffectType.EXPERIENCE_BONUS)).isEqualTo(25);
        }

        @Test
        @DisplayName("clamps the sum, not each source")
        void capsTheSum() {
            // 30 + 30 = 60, which must come out as the 50 ceiling. Capping each
            // side first would also give 50 here, but 40 + 40 shows the
            // difference: 80 capped once is 50, and so is two caps of 40.
            Map<ItemEffectType, Integer> combined = service.cap(
                    Map.of(ItemEffectType.EXPERIENCE_BONUS, 40),
                    Map.of(ItemEffectType.EXPERIENCE_BONUS, 40));

            assertThat(combined.get(ItemEffectType.EXPERIENCE_BONUS))
                    .isEqualTo(EquipmentBonusService.MAX_EXPERIENCE_BONUS);
        }

        @Test
        @DisplayName("keeps a sum under the ceiling intact")
        void leavesSmallSumsAlone() {
            Map<ItemEffectType, Integer> combined = service.cap(
                    Map.of(ItemEffectType.COIN_BONUS, 10),
                    Map.of(ItemEffectType.COIN_BONUS, 12));

            assertThat(combined.get(ItemEffectType.COIN_BONUS)).isEqualTo(22);
        }

        @ParameterizedTest(name = "{0} caps at {1}")
        @CsvSource({
                "EXPERIENCE_BONUS, 50",
                "COIN_BONUS, 50",
                "ENERGY_EFFICIENCY, 30",
                "MISSION_SPEED, 30",
                "PUZZLE_BONUS, 30"
        })
        @DisplayName("every effect type keeps the Phase 4 ceiling")
        void ceilingsAreUnchanged(ItemEffectType type, int expected) {
            Map<ItemEffectType, Integer> combined = service.cap(
                    Map.of(type, 500), Map.of(type, 500));

            assertThat(combined.get(type)).isEqualTo(expected);
        }

        @Test
        @DisplayName("omits types that contribute nothing")
        void omitsEmptyTypes() {
            Map<ItemEffectType, Integer> combined = service.cap(
                    Map.of(ItemEffectType.COIN_BONUS, 5), Map.of());

            assertThat(combined).containsOnlyKeys(ItemEffectType.COIN_BONUS);
        }
    }

    // ------------------------------------------------------------------
    // The same thing, through the real tables.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a skill bonus reaches the mission reward")
    void skillBonusAppliesToRewards() throws Exception {
        String email = "skill_reward@example.com";
        String token = signInNewPlayer("skill_reward", email);
        setSkillPoints(email, 20);

        // Signal Tracing level 1 grants +2% COIN_BONUS.
        unlockSkill(token, skillId("SIGNAL_TRACING"));

        UUID mission = missionId("RECON_PERIMETER");
        var before = profileOf(email);
        long baseCoins = missionRepository.findById(mission).orElseThrow().getCoinReward();

        completeMissionThroughPuzzle(token, mission, email);

        // The payout also has the +5% mission speed of the starter laptop, which
        // does not touch coins, so the coin figure isolates the skill bonus.
        long expected = EquipmentBonusService.applyPercentBonus(baseCoins, 2);
        long gained = profileOf(email).getCoins() - before.getCoins();
        assertThat(gained).isEqualTo(expected);
    }

    @Test
    @DisplayName("equipment and skills stack")
    void equipmentAndSkillsStack() throws Exception {
        String email = "skill_stack@example.com";
        String token = signInNewPlayer("skill_stack", email);
        setCoins(email, 5000);
        setSkillPoints(email, 20);

        // Neural Processor: +10% XP from equipment.
        purchase(token, itemId("NEURAL_PROCESSOR"));
        equip(token, com.cyberheist.shop.EquipmentSlot.PROCESSOR,
                ownedItem(email, "NEURAL_PROCESSOR").orElseThrow().getId());

        // Pattern Analysis is gated behind Cipher Mastery level 2, so the chain
        // has to be walked in order: +3% XP from the skill at level 1.
        unlockSkill(token, skillId("CIPHER_MASTERY"));
        unlockSkill(token, skillId("CIPHER_MASTERY"));
        unlockSkill(token, skillId("PATTERN_ANALYSIS"));

        int xp = playerBonusService.bonusFor(userIdOf(email), ItemEffectType.EXPERIENCE_BONUS);
        assertThat(xp).as("10 from the item plus 3 from the skill").isEqualTo(13);
    }

    @Test
    @DisplayName("a skill cannot push XP past the global ceiling")
    void combinedXpIsCapped() throws Exception {
        String email = "skill_cap@example.com";
        String token = signInNewPlayer("skill_cap", email);
        setSkillPoints(email, 200);

        // Every XP-granting skill, taken all the way. The chains must be walked
        // in prerequisite order, so each branch's root is maxed first.
        maxSkill(token, "CIPHER_MASTERY");
        maxSkill(token, "PATTERN_ANALYSIS");   // 15% XP
        maxSkill(token, "NEURAL_PROCESSING");  // 18% XP
        maxSkill(token, "SIGNAL_TRACING");
        maxSkill(token, "PACKET_ANALYSIS");    // 10% XP
        maxSkill(token, "DEEP_ACCESS");        // 18% XP

        PlayerBonusService.Breakdown breakdown = playerBonusService.breakdownFor(userIdOf(email));

        // 15 + 18 + 10 + 18 = 61 raw, well over the 50 ceiling.
        int rawSkills = breakdown.skills().getOrDefault(ItemEffectType.EXPERIENCE_BONUS, 0);
        assertThat(rawSkills).isEqualTo(61);

        assertThat(playerBonusService.bonusFor(userIdOf(email), ItemEffectType.EXPERIENCE_BONUS))
                .as("skills must not be able to escape Phase 4's ceiling")
                .isEqualTo(EquipmentBonusService.MAX_EXPERIENCE_BONUS);
    }

    /** Unlocks a skill to its maximum level, one level at a time. */
    private void maxSkill(String token, String code) throws Exception {
        for (int level = 0; level < 5; level++) {
            unlockSkill(token, skillId(code));
        }
    }

    @Test
    @DisplayName("a skill energy discount reaches the mission cost")
    void skillEnergyDiscountApplies() throws Exception {
        String email = "skill_energy@example.com";
        String token = signInNewPlayer("skill_energy", email);
        setSkillPoints(email, 20);

        grantExperience(email, 2000);
        UUID mission = missionId("CRYPTO_SECRET");
        int baseCost = missionRepository.findById(mission).orElseThrow().getEnergyCost();

        // ENERGY_SHIELD level 1 grants +3% energy efficiency.
        unlockSkill(token, skillId("ENERGY_SHIELD"));

        int charged = EquipmentBonusService.applyEnergyDiscount(baseCost, 3);
        int before = profileOf(email).getEnergy();
        startMission(token, "CRYPTO_SECRET");

        assertThat(before - profileOf(email).getEnergy()).isEqualTo(charged);
        assertThat(charged).isLessThan(baseCost).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a skill with no player_skills row contributes nothing")
    void untouchedSkillsContributeNothing() throws Exception {
        String email = "skill_untouched@example.com";
        signInNewPlayer("skill_untouched", email);

        // The catalogue exists and is unlocked-by-default-empty; a player who has
        // touched nothing gets only their starter laptop's bonus.
        int xp = playerBonusService.bonusFor(userIdOf(email), ItemEffectType.EXPERIENCE_BONUS);
        assertThat(xp).isZero();
    }

    @Test
    @DisplayName("another player's skills never affect this player")
    void bonusesAreScopedToCaller() throws Exception {
        String emailA = "skill_scope_a@example.com";
        String tokenA = signInNewPlayer("skill_scope_a", emailA);
        registerPlayer("skill_scope_b", "skill_scope_b@example.com");
        String tokenB = loginAndGetAccessToken("skill_scope_b@example.com", VALID_PASSWORD);
        setSkillPoints(emailA, 20);

        unlockSkill(tokenA, skillId("CIPHER_MASTERY"));

        // Cipher Mastery grants puzzle bonus, which A now has and B does not.
        assertThat(playerBonusService.breakdownFor(userIdOf(emailA)).skills()
                .getOrDefault(ItemEffectType.PUZZLE_BONUS, 0)).isEqualTo(2);
        assertThat(playerBonusService.breakdownFor(userIdOf("skill_scope_b@example.com"))
                .skills()).isEmpty();
        assertThat(playerBonusService.bonusFor(userIdOf("skill_scope_b@example.com"),
                ItemEffectType.PUZZLE_BONUS)).isZero();
    }
}