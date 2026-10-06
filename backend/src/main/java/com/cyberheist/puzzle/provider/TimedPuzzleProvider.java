package com.cyberheist.puzzle.provider;

import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.PuzzleChallenge;
import com.cyberheist.puzzle.PuzzleProvider;
import com.cyberheist.puzzle.PuzzleType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Code-recall puzzles under a short, server-enforced window.
 *
 * <p>The player is shown a short access code, the client hides it after a moment
 * (and the window continues to run), and the player types it back. The pressure
 * comes from the code vanishing rather than from a mechanic that is hard to
 * learn.
 *
 * <p>Timing is entirely server-side. The provider declares the window; the
 * server stamps {@code startedAt} and {@code expiresAt} and rejects a
 * submission that arrives after the window regardless of what the browser
 * claims. No elapsed time is ever accepted from the client, so widening the
 * clock does not buy extra seconds.
 */
@Component
public class TimedPuzzleProvider implements PuzzleProvider {

    /**
     * Minimum window offered.
     *
     * <p>Even ELITE keeps 12 seconds: the point is recall, not dexterity, and
     * an unpassable window would look like a bug rather than a difficulty tier.
     */
    private static final int MINIMUM_WINDOW_SECONDS = 12;

    @Override
    public PuzzleType type() {
        return PuzzleType.TIMED;
    }

    @Override
    public PuzzleChallenge create(MissionDifficulty difficulty, long seed) {
        Random random = new Random(seed);

        // Harder tiers use a longer code, which is what makes recall harder;
        // the window shrinks only slightly.
        int groups = switch (difficulty) {
            case EASY, MEDIUM -> 2;
            case HARD, ELITE -> 3;
        };
        String code = PuzzleGeneratorSupport.randomCode(random, groups, 2);

        return new PuzzleChallenge(
                PuzzleType.TIMED,
                "Beat the lock",
                "The code below is shown briefly, then the display goes dark. "
                        + "Type the code exactly as you saw it before the lock re-seals.",
                List.of(code),
                List.of(),
                code);
    }

    @Override
    public int timeLimitSeconds(MissionDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 45;
            case MEDIUM -> 35;
            case HARD -> 25;
            case ELITE -> MINIMUM_WINDOW_SECONDS;
        };
    }
}