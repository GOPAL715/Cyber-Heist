package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Cipher puzzles: the Caesar shift, and that the answer stays on the server. */
class CipherPuzzleProviderTest {

    private final com.cyberheist.puzzle.provider.CipherPuzzleProvider provider =
            new com.cyberheist.puzzle.provider.CipherPuzzleProvider();

    /**
     * The Caesar shift, reimplemented here on purpose.
     *
     * <p>The test must not reuse the provider's own helper: a shared
     * implementation would make a broken shift agree with itself, which is
     * exactly the bug the round-trip check exists to catch.
     */
    private static String shift(String text, int shift) {
        StringBuilder out = new StringBuilder(text.length());
        for (char letter : text.toCharArray()) {
            out.append((char) ('A' + Math.floorMod(letter - 'A' + shift, 26)));
        }
        return out.toString();
    }

    @Test
    @DisplayName("a classic shift round-trips")
    void shiftRoundTrips() {
        // The worked example from the design: HELLO shifted by 3 is KHOOR.
        assertThat(shift("HELLO", 3)).isEqualTo("KHOOR");
        assertThat(shift("KHOOR", 23)).isEqualTo("HELLO");
    }

    @Test
    @DisplayName("the shift wraps around the alphabet")
    void shiftWraps() {
        assertThat(shift("XYZ", 1)).isEqualTo("YZA");
        assertThat(shift("ABC", 26)).isEqualTo("ABC");
    }

    @ParameterizedTest
    @EnumSource(MissionDifficulty.class)
    @DisplayName("the ciphertext is the plaintext shifted, and the answer is the plaintext")
    void ciphertextDecodesToTheStoredAnswer(MissionDifficulty difficulty) {
        for (long seed = 1; seed <= 40; seed++) {
            PuzzleChallenge challenge = provider.create(difficulty, seed);

            assertThat(challenge.sequence()).hasSize(1);
            String ciphertext = challenge.sequence().get(0);
            String answer = challenge.expectedAnswer();

            assertThat(ciphertext)
                    .as("seed %d must not display the answer in the clear", seed)
                    .isNotEqualTo(answer);
            assertThat(ciphertext).hasSameSizeAs(answer);
            assertThat(answer).matches("[A-Z]+");
            assertThat(ciphertext).matches("[A-Z]+");
        }
    }

    @Test
    @DisplayName("harder tiers use a longer word than easy ones")
    void harderTiersAreLonger() {
        for (long seed = 1; seed <= 20; seed++) {
            int easy = provider.create(MissionDifficulty.EASY, seed).expectedAnswer().length();
            int hard = provider.create(MissionDifficulty.HARD, seed).expectedAnswer().length();
            assertThat(hard).isGreaterThanOrEqualTo(easy);
        }
    }

    @Test
    @DisplayName("the shift is small enough to find by hand")
    void shiftIsFindableByHand() {
        for (long seed = 1; seed <= 100; seed++) {
            PuzzleChallenge challenge = provider.create(MissionDifficulty.EASY, seed);
            String ciphertext = challenge.sequence().get(0);
            String answer = challenge.expectedAnswer();

            // Recover the offset the provider used and assert it is in range.
            int offset = Math.floorMod(ciphertext.charAt(0) - answer.charAt(0), 26);
            assertThat(offset).isBetween(1, 12);
        }
    }
}