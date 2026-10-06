package com.cyberheist.puzzle.provider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Small helpers shared by the deterministic providers.
 *
 * <p>Everything here is pure and seeded: given the same seed the same tokens
 * come out on every machine, which is what makes server-side re-derivation
 * reliable.
 */
final class PuzzleGeneratorSupport {

    /** Latin letters used for cipher plaintexts and node labels. */
    static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Letter count that keeps a decoded message readable on one screen. */
    static final int SHORT_WORD_LENGTH = 5;

    /** Letter count for the longer messages of the harder tiers. */
    static final int LONG_WORD_LENGTH = 7;

    /** Digits allowed in a timed code, excluding visually ambiguous pairs. */
    private static final String CODE_DIGITS = "3479";

    /** Uppercase letters allowed in a timed code, excluding visually ambiguous ones. */
    private static final String CODE_LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ";

    private PuzzleGeneratorSupport() {
    }

    /** A random element, never failing because the list is never empty. */
    static <T> T pick(Random random, List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /**
     * A short uppercase word of exactly {@code length} letters.
     *
     * <p>Generated from the alphabet rather than from a word list, so the answer
     * is exactly what the displayed ciphertext decodes to and the player has no
     * way to be "right in spirit" but wrong on a spelling.
     */
    static String randomWord(Random random, int length) {
        StringBuilder word = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            word.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        return word.toString();
    }

    /**
     * Builds distractor options for a multiple-choice challenge.
     *
     * <p>Distractors are derived from the correct answer by small, plausible
     * edits (an off-by-one neighbour, a digit swap) and are de-duplicated and
     * kept away from the answer itself, so exactly one option is correct.
     *
     * @param answer  the correct value, already rendered as text
     * @param factory produces {@code count} candidate distractors; each call to
     *                {@code apply} is passed an index and may return
     *                {@code null} to decline
     * @return exactly {@code count} distinct options, answer included, in a
     *         deterministic order
     */
    static List<String> optionsWithAnswer(Random random,
                                          String answer,
                                          int count,
                                          java.util.function.IntFunction<String> factory) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(answer);

        int guard = 0;
        while (seen.size() < count && guard < count * 40) {
            String candidate = factory.apply(guard);
            guard++;
            if (candidate == null || candidate.isBlank() || candidate.equalsIgnoreCase(answer)) {
                continue;
            }
            seen.add(candidate);
        }

        List<String> options = new ArrayList<>(seen);
        // Fisher-Yates with the seeded generator: deterministic per seed.
        for (int i = options.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = options.get(i);
            options.set(i, options.get(j));
            options.set(j, tmp);
        }
        return List.copyOf(options);
    }

    /**
     * Numeric distractors for a numeric answer: small outwards offsets, so the
     * close, tempting values are always among them. Every returned value
     * differs from the answer.
     *
     * @param count how many distractors, not counting the answer itself
     * @return a mutable list, because the caller appends the correct option
     */
    static List<String> numericDistractors(Random random, long answer, int count) {
        List<String> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(Long.toString(answer));

        // Walk outwards from the answer so the close, tempting values appear.
        for (int offset = 1; options.size() < count && offset <= 40; offset++) {
            seen.add(Long.toString(answer + offset));
            if (options.size() < count) {
                seen.add(Long.toString(answer - offset));
            }
        }
        seen.removeIf(value -> {
            try {
                return Long.parseLong(value) == answer;
            } catch (NumberFormatException ex) {
                return true;
            }
        });

        for (String value : seen) {
            options.add(value);
            if (options.size() == count) {
                break;
            }
        }

        for (int i = options.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = options.get(i);
            options.set(i, options.get(j));
            options.set(j, tmp);
        }
        return options;
    }

    /**
     * A memorisation code such as {@code AX7-92Q}.
     *
     * <p>Built from a deliberately ambiguous-free alphabet: no {@code 0}/{code O},
     * no {@code 1}/{@code I}/{@code L}, so a player transcribing what they saw
     * cannot be failed by a character the display rendered ambiguously.
     */
    static String randomCode(Random random, int groups, int groupLength) {
        StringBuilder code = new StringBuilder();
        for (int g = 0; g < groups; g++) {
            if (g > 0) {
                code.append('-');
            }
            for (int i = 0; i < groupLength; i++) {
                boolean digit = random.nextBoolean();
                code.append(digit
                        ? CODE_DIGITS.charAt(random.nextInt(CODE_DIGITS.length()))
                        : CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
            }
        }
        return code.toString();
    }
}