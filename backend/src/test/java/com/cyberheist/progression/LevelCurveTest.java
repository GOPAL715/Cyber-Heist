package com.cyberheist.progression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the level curve. Pure arithmetic, no Spring context. */
class LevelCurveTest {

    private LevelCurve curve() {
        return new LevelCurve(new ProgressionProperties(100L, 1.5d, 100));
    }

    @Test
    @DisplayName("level 1 requires no experience")
    void levelOneIsFree() {
        assertThat(curve().xpRequiredFor(1)).isZero();
    }

    @Test
    @DisplayName("the cost to advance from each level follows the 1.5x growth curve")
    void advancementCostsMatchTheCurve() {
        LevelCurve curve = curve();

        assertThat(curve.xpForNextLevel(1)).isEqualTo(100);
        assertThat(curve.xpForNextLevel(2)).isEqualTo(150);
        assertThat(curve.xpForNextLevel(3)).isEqualTo(225);
        // 337.5 rounds to 338
        assertThat(curve.xpForNextLevel(4)).isEqualTo(338);
    }

    @Test
    @DisplayName("cumulative totals are the sum of the per-level costs")
    void cumulativeTotalsMatchTheCurve() {
        LevelCurve curve = curve();

        assertThat(curve.xpRequiredFor(1)).isEqualTo(0);
        assertThat(curve.xpRequiredFor(2)).isEqualTo(100);   // 100
        assertThat(curve.xpRequiredFor(3)).isEqualTo(250);   // 100 + 150
        assertThat(curve.xpRequiredFor(4)).isEqualTo(475);   // + 225
        assertThat(curve.xpRequiredFor(5)).isEqualTo(813);   // + 812.5, rounds up
    }

    @Test
    @DisplayName("thresholds are strictly increasing")
    void thresholdsAreMonotonic() {
        LevelCurve curve = curve();

        for (int level = 2; level < 30; level++) {
            assertThat(curve.xpRequiredFor(level + 1))
                    .as("level %d -> %d", level, level + 1)
                    .isGreaterThan(curve.xpRequiredFor(level));
        }
    }

    @Test
    @DisplayName("experience below the threshold keeps the player at the current level")
    void belowThreshold() {
        assertThat(curve().levelFor(0)).isEqualTo(1);
        assertThat(curve().levelFor(99)).isEqualTo(1);
    }

    @Test
    @DisplayName("experience exactly on the threshold levels up")
    void exactlyOnThreshold() {
        LevelCurve curve = curve();

        assertThat(curve.levelFor(100)).isEqualTo(2);
        assertThat(curve.levelFor(250)).isEqualTo(3);
        assertThat(curve.levelFor(475)).isEqualTo(4);
    }

    @Test
    @DisplayName("experience just below the threshold does not level up")
    void justBelowThreshold() {
        LevelCurve curve = curve();

        assertThat(curve.levelFor(99)).isEqualTo(1);
        assertThat(curve.levelFor(249)).isEqualTo(2);
        assertThat(curve.levelFor(474)).isEqualTo(3);
    }

    @Test
    @DisplayName("the spec example: 90 XP plus a 50 XP reward lands on level 2 holding 140 XP")
    void cumulativeXpIsNotResetOnLevelUp() {
        assertThat(curve().levelFor(140)).isEqualTo(2);
    }

    @Test
    @DisplayName("a large amount of experience can cross several levels at once")
    void multipleLevelJump() {
        LevelCurve curve = curve();

        // 1318.75 rounds to 1319, so 1319 XP is exactly the level 6 threshold.
        assertThat(curve.levelFor(1318)).isEqualTo(5);
        assertThat(curve.levelFor(1319)).isEqualTo(6);
    }

    @Test
    @DisplayName("the level cap is respected for absurd experience totals")
    void respectsMaximumLevel() {
        LevelCurve curve = curve();

        assertThat(curve.levelFor(Long.MAX_VALUE)).isEqualTo(curve.maximumLevel());
        // At the cap there is no next level, so no divisor is required.
        assertThat(curve.xpForNextLevel(curve.maximumLevel())).isZero();
    }

    @Test
    @DisplayName("experience into the level is measured from the level's threshold")
    void xpIntoLevel() {
        LevelCurve curve = curve();

        assertThat(curve.xpIntoLevel(1, 70)).isEqualTo(70);
        // 140 XP while at level 2 means 40 XP past the level 2 threshold of 100.
        assertThat(curve.xpIntoLevel(2, 140)).isEqualTo(40);
    }

    @Test
    @DisplayName("a custom curve is honoured")
    void supportsCustomCurve() {
        LevelCurve gentle = new LevelCurve(new ProgressionProperties(50L, 2.0d, 10));

        // 50 to leave level 1, 100 to leave level 2.
        assertThat(gentle.xpRequiredFor(2)).isEqualTo(50);
        assertThat(gentle.xpRequiredFor(3)).isEqualTo(150);
        assertThat(gentle.levelFor(150)).isEqualTo(3);
    }

    @Test
    @DisplayName("nonsensical configuration falls back to safe defaults")
    void rejectsInvalidConfiguration() {
        ProgressionProperties properties = new ProgressionProperties(0L, 0.5d, 0);

        assertThat(properties.baseExperience()).isEqualTo(100);
        assertThat(properties.growthMultiplier()).isEqualTo(1.5d);
        assertThat(properties.maximumLevel()).isEqualTo(100);
    }
}