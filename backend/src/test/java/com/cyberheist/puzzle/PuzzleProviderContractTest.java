package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The puzzle engine's own contract, tested without a database.
 *
 * <p>These are the properties every provider must satisfy, checked against
 * every registered provider at every difficulty. They are the cheapest place to
 * catch a broken generator, because a generator that produces two correct
 * options or a different challenge from the same seed would otherwise only show
 * up as a player quietly losing.
 */
class PuzzleProviderContractTest {

    /** How many distinct seeds each property is checked across. */
    private static final int SAMPLE_SEEDS = 200;

    private final PuzzleService service =
            new PuzzleService(List.of(
                    new com.cyberheist.puzzle.provider.CipherPuzzleProvider(),
                    new com.cyberheist.puzzle.provider.SequencePuzzleProvider(),
                    new com.cyberheist.puzzle.provider.PatternPuzzleProvider(),
                    new com.cyberheist.puzzle.provider.LogicPuzzleProvider(),
                    new com.cyberheist.puzzle.provider.TimedPuzzleProvider()));

    @Nested
    @DisplayName("registry")
    class Registry {

        @Test
        @DisplayName("every declared puzzle type has a provider")
        void everyTypeHasAProvider() {
            assertThat(service.supportedTypes())
                    .containsExactlyInAnyOrder(PuzzleType.values());
        }

        @Test
        @DisplayName("a missing provider fails at startup, not at play time")
        void missingProviderFailsFast() {
            assertThatThrownBy(() -> new PuzzleService(List.of(
                    new com.cyberheist.puzzle.provider.CipherPuzzleProvider())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No PuzzleProvider is registered for type");
        }

        @Test
        @DisplayName("two providers claiming one type fail at startup")
        void duplicateProviderFailsFast() {
            assertThatThrownBy(() -> new PuzzleService(List.of(
                    new com.cyberheist.puzzle.provider.CipherPuzzleProvider(),
                    new com.cyberheist.puzzle.provider.CipherPuzzleProvider())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Two PuzzleProvider beans claim type");
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("the same seed yields the same challenge, every time")
        void sameSeedSameChallenge(PuzzleType type) {
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                PuzzleChallenge first = service.regenerate(type, difficulty, 42L).challenge();
                PuzzleChallenge second = service.regenerate(type, difficulty, 42L).challenge();
                assertThat(first)
                        .as("%s at %s must be reproducible from its seed", type, difficulty)
                        .isEqualTo(second);
            }
        }

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("the generator responds to its seed")
        void differentSeedsDiffer(PuzzleType type) {
            Set<String> distinct = new HashSet<>();
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                for (long seed = 1; seed <= SAMPLE_SEEDS; seed++) {
                    PuzzleChallenge challenge =
                            service.regenerate(type, difficulty, seed).challenge();
                    distinct.add(challenge.sequence() + "|" + challenge.expectedAnswer());
                }
            }
            // Deliberately not "every seed is unique": a five-letter cipher or a
            // three-node pattern has a finite answer space and will collide. What
            // must not happen is a generator that ignores its seed and returns the
            // same handful of puzzles every time.
            assertThat(distinct.size())
                    .as("%s produced only %d distinct challenges across %d seeds",
                            type, distinct.size(), SAMPLE_SEEDS * 4)
                    .isGreaterThan(100);
        }
    }

    @Nested
    @DisplayName("generality")
    class Generality {

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("every challenge is solvable and renders something")
        void challengesAreWellFormed(PuzzleType type) {
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                for (long seed = 1; seed <= 60; seed++) {
                    PuzzleChallenge challenge =
                            service.regenerate(type, difficulty, seed).challenge();

                    String where = type + "/" + difficulty + "/seed " + seed;

                    assertThat(challenge.type()).as("%s reports its own type", where).isEqualTo(type);
                    assertThat(challenge.title()).as("%s has a title", where).isNotBlank();
                    assertThat(challenge.question()).as("%s has a question", where).isNotBlank();
                    assertThat(challenge.sequence())
                            .as("%s shows at least one display token", where)
                            .isNotEmpty();
                    assertThat(challenge.expectedAnswer()).as("%s has an answer", where).isNotBlank();
                    assertThat(challenge.sequence())
                            .as("%s display tokens are not blank", where)
                            .noneMatch(String::isBlank);
                }
            }
        }

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("multiple-choice options are distinct and include the answer")
        void optionsAreDistinctAndContainTheAnswer(PuzzleType type) {
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                for (long seed = 1; seed <= 60; seed++) {
                    PuzzleChallenge challenge =
                            service.regenerate(type, difficulty, seed).challenge();
                    if (!challenge.isMultipleChoice()) {
                        continue;
                    }
                    String where = type + "/" + difficulty + "/seed " + seed;

                    assertThat(new HashSet<>(challenge.options()))
                            .as("%s must not repeat an option", where)
                            .hasSize(challenge.options().size());
                    assertThat(challenge.options())
                            .as("%s must offer the correct answer", where)
                            .anyMatch(option -> PuzzleAnswers.matches(option, challenge.expectedAnswer()));
                }
            }
        }

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("every challenge has a positive answer window")
        void windowsArePositive(PuzzleType type) {
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                assertThat(service.regenerate(type, difficulty, 7L).window())
                        .as("%s at %s must have a positive window", type, difficulty)
                        .isPositive();
            }
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("the generated answer validates and nothing else does")
        void onlyTheRealAnswerPasses(PuzzleType type) {
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                for (long seed = 1; seed <= 40; seed++) {
                    String answer = service.regenerate(type, difficulty, seed).challenge().expectedAnswer();
                    String where = type + "/" + difficulty + "/seed " + seed;

                    assertThat(service.isCorrect(type, difficulty, seed, answer))
                            .as("%s must accept its own answer", where)
                            .isTrue();

                    assertThat(service.isCorrect(type, difficulty, seed, null))
                            .as("%s must reject a missing answer", where)
                            .isFalse();
                    assertThat(service.isCorrect(type, difficulty, seed, "   "))
                            .as("%s must reject a blank answer", where)
                            .isFalse();
                    assertThat(service.isCorrect(type, difficulty, seed, answer + "X"))
                            .as("%s must reject a corrupted answer", where)
                            .isFalse();
                }
            }
        }

        @ParameterizedTest
        @EnumSource(PuzzleType.class)
        @DisplayName("cosmetic differences in the answer still count as correct")
        void answersAreNormalised(PuzzleType type) {
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                String answer = service.regenerate(type, difficulty, 11L).challenge().expectedAnswer();

                assertThat(service.isCorrect(type, difficulty, 11L, "  " + answer + "  "))
                        .as("%s trims whitespace", type).isTrue();
                assertThat(service.isCorrect(type, difficulty, 11L, answer.toLowerCase(java.util.Locale.ROOT)))
                        .as("%s is case insensitive", type).isTrue();
                assertThat(service.isCorrect(type, difficulty, 11L, answer + "."))
                        .as("%s tolerates a trailing period", type).isTrue();
            }
        }

        @ParameterizedTest
        @EnumSource(value = PuzzleType.class, names = {"CIPHER", "SEQUENCE"})
        @DisplayName("another puzzle's answer is rejected almost every time")
        void answersAreNotInterchangeableBetweenPuzzles(PuzzleType type) {
            // Restricted to the two types with a large answer space. A pattern
            // answers with one of eight symbols, so a foreign answer collides
            // constantly - that is a property of the puzzle, not a flaw, and the
            // integration suite covers cross-puzzle rejection through the API.
            for (MissionDifficulty difficulty : MissionDifficulty.values()) {
                int checked = 0;
                int wronglyAccepted = 0;

                for (long seed = 1; seed <= 30; seed++) {
                    String answer =
                            service.regenerate(type, difficulty, seed).challenge().expectedAnswer();
                    for (long otherSeed = seed + 1; otherSeed <= 40; otherSeed++) {
                        checked++;
                        if (service.isCorrect(type, difficulty, otherSeed, answer)) {
                            wronglyAccepted++;
                        }
                    }
                }

                assertThat(wronglyAccepted)
                        .as("%s at %s accepted %d of %d foreign answers",
                                type, difficulty, wronglyAccepted, checked)
                        .isLessThan(checked / 10);
            }
        }
    }
}