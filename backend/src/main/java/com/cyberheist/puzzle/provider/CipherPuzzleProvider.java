package com.cyberheist.puzzle.provider;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleChallenge;
import com.cyberheist.puzzle.PuzzleProvider;
import com.cyberheist.puzzle.PuzzleType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Caesar-style substitution puzzles.
 *
 * <p>The player is shown a word shifted by a fixed offset and asked to decode
 * it. The offset is always in 1–12 and is <em>not</em> revealed, so the player
 * genuinely has to test shifts; it is nevertheless derivable from the stored
 * seed, which is how the answer is validated later without being persisted.
 *
 * <p>Generation is a pure function of the seed: the first draw is the shift,
 * the rest is the plaintext.
 */
@Component
public class CipherPuzzleProvider implements PuzzleProvider {

    /** Largest shift offered. Half the alphabet keeps brute force trivial by hand. */
    private static final int MAX_SHIFT = 12;

    @Override
    public PuzzleType type() {
        return PuzzleType.CIPHER;
    }

    @Override
    public PuzzleChallenge create(MissionDifficulty difficulty, long seed) {
        Random random = new Random(seed);

        int shift = 1 + random.nextInt(MAX_SHIFT);
        int length = wordLength(difficulty);
        String plaintext = PuzzleGeneratorSupport.randomWord(random, length);
        String ciphertext = shift(plaintext, shift);

        return new PuzzleChallenge(
                PuzzleType.CIPHER,
                "Decode the transmission",
                "The intercept below was shifted by a fixed Caesar offset. "
                        + "Type the decoded message in capitals.",
                List.of(ciphertext),
                List.of(),
                plaintext);
    }

    @Override
    public int timeLimitSeconds(MissionDifficulty difficulty) {
        // Caesar is mechanical once the offset is found, so the window is generous.
        return switch (difficulty) {
            case EASY -> 240;
            case MEDIUM -> 270;
            case HARD -> 300;
            case ELITE -> 360;
        };
    }

    /** Harder tiers hide a longer word; the mechanic never changes. */
    private int wordLength(MissionDifficulty difficulty) {
        return switch (difficulty) {
            case EASY, MEDIUM -> PuzzleGeneratorSupport.SHORT_WORD_LENGTH;
            case HARD, ELITE -> PuzzleGeneratorSupport.LONG_WORD_LENGTH;
        };
    }

    /**
     * Shifts every letter forwards by {@code shift} positions.
     *
     * <p>Only {@code A-Z} is used in generation, so there are no edge cases
     * around accented or non-Latin characters.
     */
    static String shift(String text, int shift) {
        StringBuilder shifted = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char letter = text.charAt(i);
            int offset = (letter - 'A' + shift) % 26;
            shifted.append((char) ('A' + offset));
        }
        return shifted.toString();
    }
}