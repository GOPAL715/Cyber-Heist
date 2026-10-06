package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.provider.SequencePuzzleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sequence puzzles: generation is deterministic and the answer is derivable from
 * the prompt alone.
 */
class SequencePuzzleProviderTest {

    private final SequencePuzzleProvider provider = new SequencePuzzleProvider();

    @Test
    @DisplayName("the same seed always produces the same puzzle")
    void generationIsDeterministic() {
        for (MissionDifficulty difficulty : MissionDifficulty.values()) {
            for (long seed = 1; seed <= 50; seed++) {
                PuzzleChallenge first = provider.create(difficulty, seed);
                PuzzleChallenge second = provider.create(difficulty, seed);

                assertThat(first.sequence()).isEqualTo(second.sequence());
                assertThat(first.options()).isEqualTo(second.options());
                assertThat(first.expectedAnswer()).isEqualTo(second.expectedAnswer());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("four whole terms are shown followed by a question mark")
    void showsFourTermsAndABlank(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);
            List<String> sequence = challenge.sequence();

            assertThat(sequence).hasSize(5);
            assertThat(sequence.get(4)).isEqualTo("?");
            assertThat(sequence.subList(0, 4)).allMatch(term -> term.matches("-?\\d+"));
        }
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("the answer is the value one step past the last shown term")
    void answerContinuesTheSequence(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);
            long[] terms = challenge.sequence().subList(0, 4).stream()
                    .mapToLong(Long::parseLong)
                    .toArray();

            String answer = challenge.expectedAnswer();
            assertThat(Long.parseLong(answer))
                    .as("seed %d must continue the rule, not repeat a term", seed)
                    .isNotEqualTo(terms[0])
                    .isNotEqualTo(terms[1])
                    .isNotEqualTo(terms[2])
                    .isNotEqualTo(terms[3]);

            // Every generated rule is strictly increasing, which is what makes
            // "the next value" unambiguous.
            assertThat(terms[1]).isGreaterThan(terms[0]);
            assertThat(terms[2]).isGreaterThan(terms[1]);
            assertThat(terms[3]).isGreaterThan(terms[2]);
            assertThat(Long.parseLong(answer)).isGreaterThan(terms[3]);
        }
    }

    @Test
    @DisplayName("the prompt names the rule, so the answer is not a guess")
    void promptNamesTheRule() {
        for (long seed = 1; seed <= 60; seed++) {
            String question = provider.create(MissionDifficulty.HARD, seed).question();
            assertThat(question)
                    .as("seed %d must state how the sequence advances", seed)
                    .containsAnyOf("increases by the same amount",
                            "multiplied by the same factor",
                            "operations alternate");
        }
    }

    @Test
    @DisplayName("easy tiers never use the alternating rule")
    void easyTiersAvoidTheHardestRule() {
        for (long seed = 1; seed <= 100; seed++) {
            assertThat(provider.create(MissionDifficulty.EASY, seed).question())
                    .doesNotContain("alternate");
        }
    }

    @Test
    @DisplayName("options are four distinct whole numbers including the answer")
    void optionsAreWellFormed() {
        for (long seed = 1; seed <= 50; seed++) {
            PuzzleChallenge challenge = provider.create(MissionDifficulty.EASY, seed);

            assertThat(challenge.options()).hasSize(4);
            assertThat(new java.util.HashSet<>(challenge.options())).hasSize(4);
            assertThat(challenge.options()).allMatch(option -> option.matches("-?\\d+"));
            assertThat(challenge.options()).contains(challenge.expectedAnswer());
        }
    }
}