package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.provider.TimedPuzzleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Timed puzzles: a short window and a code the player has to recall. */
class TimedPuzzleProviderTest {

    private final TimedPuzzleProvider provider = new TimedPuzzleProvider();

    @Test
    @DisplayName("the same seed always produces the same code")
    void generationIsDeterministic() {
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            for (long seed = 1; seed <= 40; seed++) {
                assertThat(provider.create(difficulty, seed))
                        .isEqualTo(provider.create(difficulty, seed));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("the answer is the displayed code, and it is unambiguous to transcribe")
    void answerIsTheDisplayedCode(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);

            assertThat(challenge.sequence()).hasSize(1);
            String code = challenge.sequence().get(0);

            assertThat(challenge.expectedAnswer()).isEqualTo(code);
            assertThat(challenge.isMultipleChoice()).isFalse();
            assertThat(code).matches("[A-Z0-9]{2}(-[A-Z0-9]{2})+");

            // No characters that render ambiguously, so a player who reads the
            // code correctly cannot be failed on transcription.
            assertThat(code).doesNotContain("I").doesNotContain("O")
                    .doesNotContain("0").doesNotContain("1").doesNotContain("L");
        }
    }

    @Test
    @DisplayName("harder tiers use a longer code")
    void harderTiersAreLonger() {
        for (long seed = 1; seed <= 30; seed++) {
            assertThat(provider.create(MissionDifficulty.HARD, seed).expectedAnswer().length())
                    .isGreaterThan(provider.create(MissionDifficulty.EASY, seed).expectedAnswer().length());
        }
    }

    @Test
    @DisplayName("the window is shorter than any untimed puzzle's")
    void windowIsTighterThanOtherPuzzleTypes() {
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            int timed = provider.timeLimitSeconds(difficulty);
            int cipher = new com.cyberheist.puzzle.provider.CipherPuzzleProvider()
                    .timeLimitSeconds(difficulty);

            assertThat(timed)
                    .as("%s must be the most urgent puzzle", difficulty)
                    .isLessThan(cipher)
                    .isGreaterThanOrEqualTo(12);
        }
    }

    @Test
    @DisplayName("the window narrows as difficulty rises")
    void windowNarrowsWithDifficulty() {
        int previous = Integer.MAX_VALUE;
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            int seconds = provider.timeLimitSeconds(difficulty);
            assertThat(seconds).isLessThanOrEqualTo(previous);
            previous = seconds;
        }
    }

    @Test
    @DisplayName("different seeds mostly produce different codes")
    void codesVaryWithTheSeed() {
        var distinct = new HashSet<String>();
        for (long seed = 1; seed <= 100; seed++) {
            distinct.add(provider.create(MissionDifficulty.EASY, seed).expectedAnswer());
        }
        assertThat(distinct.size()).isGreaterThan(50);
    }
}