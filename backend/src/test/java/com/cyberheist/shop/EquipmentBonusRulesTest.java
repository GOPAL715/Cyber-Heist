package com.cyberheist.shop;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import com.cyberheist.reward.Reward;
import java.util.UUID;
import com.cyberheist.reward.RewardService;
import java.util.UUID;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import java.util.UUID;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The bonus arithmetic, with no database involved.
 *
 * <p>These are the rules the economy depends on, so they are pinned here rather
 * than only being observed through endpoints. In particular the rounding tests
 * exist because floating point was deliberately rejected: a payout that varied
 * with the binary representation of a decimal would be a real bug.
 */
class EquipmentBonusRulesTest {

    @Nested
    @DisplayName("percentage bonuses")
    class PercentBonuses {

        @ParameterizedTest(name = "{0} at +{1}% is {2}")
        @CsvSource({
                // base, percent, expected
                "50, 10, 55",    // 5 exactly
                "50, 5, 53",     // 2.5 rounds half up
                "28, 15, 32",    // 4.2 rounds down
                "120, 15, 138",  // 18 exactly
                "25, 8, 27",     // 2 exactly
                "190, 12, 213",  // 22.8 rounds up
                "1000, 30, 1300"
        })
        void roundsHalfUpDeterministically(long base, int percent, long expected) {
            assertThat(EquipmentBonusService.applyPercentBonus(base, percent)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a zero or negative bonus changes nothing")
        void zeroBonusIsIdentity() {
            assertThat(EquipmentBonusService.applyPercentBonus(50, 0)).isEqualTo(50);
            assertThat(EquipmentBonusService.applyPercentBonus(50, -20)).isEqualTo(50);
        }

        @Test
        @DisplayName("never produces a negative amount")
        void neverNegative() {
            assertThat(EquipmentBonusService.applyPercentBonus(0, 50)).isZero();
        }

        @Test
        @DisplayName("is stable: the same inputs always give the same output")
        void isDeterministic() {
            for (int base = 1; base <= 200; base++) {
                for (int percent = 0; percent <= 50; percent++) {
                    long first = EquipmentBonusService.applyPercentBonus(base, percent);
                    long second = EquipmentBonusService.applyPercentBonus(base, percent);
                    assertThat(second).as("base=%d percent=%d", base, percent).isEqualTo(first);
                }
            }
        }
    }

    @Nested
    @DisplayName("energy discounts")
    class EnergyDiscounts {

        @ParameterizedTest(name = "{0} energy at {1}% is {2}")
        @CsvSource({
                "20, 10, 18",   // the documented example
                "20, 0, 20",
                // 50% is clamped to the 30% efficiency cap, so 10 becomes 7.
                "10, 50, 7",
                "18, 10, 16",
                "11, 10, 10"    // 9.9 rounds to 10
        })
        void discountsDeterministically(int base, int percent, int expected) {
            assertThat(EquipmentBonusService.applyEnergyDiscount(base, percent)).isEqualTo(expected);
        }

        @Test
        @DisplayName("never reduces a mission to free")
        void neverReachesZero() {
            // Even at the maximum efficiency a mission costs one energy.
            assertThat(EquipmentBonusService.applyEnergyDiscount(1, EquipmentBonusService.MAX_ENERGY_EFFICIENCY))
                    .isEqualTo(1);
            assertThat(EquipmentBonusService.applyEnergyDiscount(2, EquipmentBonusService.MAX_ENERGY_EFFICIENCY))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("is clamped to the efficiency cap even if given a larger bonus")
        void clampsToCap() {
            assertThat(EquipmentBonusService.applyEnergyDiscount(20, 100))
                    .isEqualTo(EquipmentBonusService.applyEnergyDiscount(
                            20, EquipmentBonusService.MAX_ENERGY_EFFICIENCY));
        }
    }

    @Nested
    @DisplayName("caps")
    class Caps {

        @Test
        @DisplayName("clamp each effect type at its ceiling")
        void clampsEachType() {
            EquipmentBonusService service = new EquipmentBonusService(null, null, null);

            assertThat(service.cappedBonus(ItemEffectType.EXPERIENCE_BONUS, 500))
                    .isEqualTo(EquipmentBonusService.MAX_EXPERIENCE_BONUS);
            assertThat(service.cappedBonus(ItemEffectType.COIN_BONUS, 500))
                    .isEqualTo(EquipmentBonusService.MAX_COIN_BONUS);
            assertThat(service.cappedBonus(ItemEffectType.ENERGY_EFFICIENCY, 500))
                    .isEqualTo(EquipmentBonusService.MAX_ENERGY_EFFICIENCY);
            assertThat(service.cappedBonus(ItemEffectType.MISSION_SPEED, 500))
                    .isEqualTo(EquipmentBonusService.MAX_MISSION_SPEED);
            assertThat(service.cappedBonus(ItemEffectType.PUZZLE_BONUS, 500))
                    .isEqualTo(EquipmentBonusService.MAX_PUZZLE_BONUS);
        }

        @Test
        @DisplayName("leave an in-range total alone")
        void leavesInRangeAlone() {
            EquipmentBonusService service = new EquipmentBonusService(null, null, null);
            assertThat(service.cappedBonus(ItemEffectType.EXPERIENCE_BONUS, 25)).isEqualTo(25);
        }

        @Test
        @DisplayName("treat a negative total as zero")
        void floorsAtZero() {
            EquipmentBonusService service = new EquipmentBonusService(null, null, null);
            assertThat(service.cappedBonus(ItemEffectType.COIN_BONUS, -10)).isZero();
        }
    }

    @Nested
    @DisplayName("reward modification")
    class RewardModification {

        @Test
        @DisplayName("adds base and equipment bonus to reach the final reward")
        void addsBonusToBase() {
            Reward finalReward = RewardService.applyBonuses(
                    new Reward(50, 25), Map.of(ItemEffectType.EXPERIENCE_BONUS, 10));

            assertThat(finalReward.experience()).isEqualTo(55);
            assertThat(finalReward.coins()).isEqualTo(25);
        }

        @Test
        @DisplayName("leaves a reward untouched with no bonuses")
        void noBonusesIsIdentity() {
            Reward base = new Reward(50, 25);
            assertThat(RewardService.applyBonuses(base, Map.of())).isEqualTo(base);
        }

        @Test
        @DisplayName("applies each type to its own amount")
        void appliesTypesIndependently() {
            Reward result = RewardService.applyBonuses(
                    new Reward(100, 200),
                    Map.of(ItemEffectType.EXPERIENCE_BONUS, 50, ItemEffectType.COIN_BONUS, 25));

            assertThat(result.experience()).isEqualTo(150);
            assertThat(result.coins()).isEqualTo(250);
        }
    }
}