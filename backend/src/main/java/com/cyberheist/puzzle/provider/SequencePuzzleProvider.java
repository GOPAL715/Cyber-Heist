package com.cyberheist.puzzle.provider;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleChallenge;
import com.cyberheist.puzzle.PuzzleProvider;
import com.cyberheist.puzzle.PuzzleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Number-sequence puzzles: {@code 2, 4, 8, 16, ?}.
 *
 * <p>Three unambiguous families are generated - constant addition, constant
 * multiplication and a two-step alternating operation - and the prompt always
 * names the family. That naming is deliberate: without it, several rules can
 * fit the same prefix and the challenge would be a coin flip rather than a
 * puzzle.
 *
 * <p>Every value is a whole number and every term is strictly increasing for
 * the generated parameter ranges, so "the next value" is never ambiguous.
 */
@Component
public class SequencePuzzleProvider implements PuzzleProvider {

    /** How many terms are revealed before the {@code ?}. */
    private static final int REVEALED_TERMS = 4;

    /** Options offered, including the correct one. */
    private static final int OPTION_COUNT = 4;

    @Override
    public PuzzleType type() {
        return PuzzleType.SEQUENCE;
    }

    @Override
    public PuzzleChallenge create(MissionDifficulty difficulty, long seed) {
        Random random = new Random(seed);
        Rule rule = Rule.pick(random, difficulty);
        long[] terms = rule.build(random, difficulty, REVEALED_TERMS + 1);

        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < REVEALED_TERMS; i++) {
            sequence.add(Long.toString(terms[i]));
        }
        sequence.add("?");

        long answer = terms[REVEALED_TERMS];
        List<String> options = PuzzleGeneratorSupport.numericDistractors(random, answer, OPTION_COUNT - 1);
        options.add(Long.toString(answer));
        options = shuffled(options, random);

        return new PuzzleChallenge(
                PuzzleType.SEQUENCE,
                "Find the next number",
                rule.prompt(),
                sequence,
                options,
                Long.toString(answer));
    }

    @Override
    public int timeLimitSeconds(MissionDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 120;
            case MEDIUM -> 150;
            case HARD -> 180;
            case ELITE -> 210;
        };
    }

    /** Deterministic Fisher-Yates over a seeded generator. */
    private static List<String> shuffled(List<String> values, Random random) {
        List<String> copy = new ArrayList<>(values);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
        }
        return copy;
    }

    /**
     * The families of rule a sequence may follow.
     *
     * <p>Each one knows how to describe itself to the player, so the challenge
     * never depends on the player guessing the rule as well as computing it.
     */
    private enum Rule {

        /** {@code 3, 7, 11, 15, 19} - add a constant. */
        ADDITION("Each term increases by the same amount. What number comes next?"),

        /** {@code 2, 6, 18, 54, 162} - multiply by a constant. */
        MULTIPLICATION("Each term is multiplied by the same factor. What number comes next?"),

        /** {@code 3, 12, 5, 20, 7, 40} - alternate two operations. */
        ALTERNATING("The operations alternate: add, multiply, add, multiply. "
                + "What number comes next?");

        private final String prompt;

        Rule(String prompt) {
            this.prompt = prompt;
        }

        String prompt() {
            return prompt;
        }

        /**
         * Picks a rule, favouring the simpler ones at lower tiers.
         *
         * <p>EASY never sees the alternating rule, which is the one a player is
         * most likely to misread.
         */
        static Rule pick(Random random, MissionDifficulty difficulty) {
            return switch (difficulty) {
                case EASY -> random.nextBoolean() ? ADDITION : MULTIPLICATION;
                case MEDIUM -> random.nextInt(3) == 0 ? ADDITION : MULTIPLICATION;
                case HARD, ELITE -> values()[random.nextInt(values().length)];
            };
        }

        /** Builds {@code count} terms, the last of which is the answer. */
        long[] build(Random random, MissionDifficulty difficulty, int count) {
            return switch (this) {
                case ADDITION -> arithmetic(random, difficulty, count);
                case MULTIPLICATION -> geometric(random, difficulty, count);
                case ALTERNATING -> alternating(random, difficulty, count);
            };
        }

        /** Constant step. Positive for every tier so the sequence grows. */
        private long[] arithmetic(Random random, MissionDifficulty difficulty, int count) {
            int maxStep = switch (difficulty) {
                case EASY -> 5;
                case MEDIUM -> 9;
                case HARD -> 14;
                case ELITE -> 25;
            };
            long step = 1 + random.nextInt(maxStep);
            long start = 1 + random.nextInt(12);

            long[] terms = new long[count];
            for (int i = 0; i < count; i++) {
                terms[i] = start + step * i;
            }
            return terms;
        }

        /** Constant factor of 2 or 3, which keeps the terms short enough to read. */
        private long[] geometric(Random random, MissionDifficulty difficulty, int count) {
            long factor = switch (difficulty) {
                case EASY -> 2;
                case MEDIUM -> random.nextBoolean() ? 2 : 3;
                case HARD, ELITE -> random.nextBoolean() ? 2 : 3;
            };
            long start = 1 + random.nextInt(4);

            long[] terms = new long[count];
            for (int i = 0; i < count; i++) {
                terms[i] = start;
                for (int m = 0; m < i; m++) {
                    terms[i] *= factor;
                }
            }
            return terms;
        }

        /**
         * Two operations applied in turn: {@code +add} then {@code ×factor}.
         *
         * <p>The prompt states the order, so the sequence is fully determined.
         * With four revealed terms plus the answer the pattern is visible
         * without guessing.
         */
        private long[] alternating(Random random, MissionDifficulty difficulty, int count) {
            long add = 1 + random.nextInt(switch (difficulty) {
                case EASY, MEDIUM -> 4;
                case HARD -> 7;
                case ELITE -> 10;
            });
            long factor = switch (difficulty) {
                case EASY, MEDIUM -> 2;
                case HARD -> 2;
                case ELITE -> random.nextBoolean() ? 2 : 3;
            };
            long start = 1 + random.nextInt(6);

            long[] terms = new long[count];
            terms[0] = start;
            for (int i = 1; i < count; i++) {
                terms[i] = (i % 2 == 1) ? terms[i - 1] + add : terms[i - 1] * factor;
            }
            return terms;
        }
    }
}