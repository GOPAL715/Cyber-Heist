package com.cyberheist.puzzle;

import com.cyberheist.mission.MissionDifficulty;

/**
 * Strategy interface for generating one family of puzzle.
 *
 * <p>This is the extension point of the puzzle engine. To add a puzzle type,
 * implement this interface as a Spring {@code @Component}; {@link PuzzleService}
 * collects every implementation at startup and indexes it by
 * {@link #type()}. No {@code if (type == …)} chain grows anywhere, and
 * {@code MissionService} never learns the name of a puzzle type.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><strong>Deterministic.</strong> {@link #create(MissionDifficulty, long)}
 *       must return an identical challenge for the same seed and difficulty, on
 *       any machine and any JVM version. That is what lets the server re-derive
 *       and validate an answer later without storing it. Use
 *       {@link java.util.Random}, whose algorithm is fixed by the JDK
 *       specification, never {@code RandomGenerator} implementations whose
 *       behaviour is not.</li>
 *   <li><strong>Solvable and unambiguous.</strong> Exactly one answer must be
 *       correct, and a competent player must be able to reach it from the
 *       displayed tokens alone.</li>
 *   <li><strong>Distinct options.</strong> When {@code options} is non-empty it
 *       must not contain duplicates, and the distractors must not be defensible
 *       alternative answers.</li>
 *   <li><strong>Pure.</strong> A provider may read no player state and write
 *       none. It never touches XP, coins, energy or mission progress; it only
 *       decides whether an answer is right.</li>
 * </ul>
 */
public interface PuzzleProvider {

    /** The single puzzle type this provider is responsible for. */
    PuzzleType type();

    /**
     * Builds a challenge deterministically from {@code seed}.
     *
     * @param difficulty the mission's difficulty, which selects how demanding the
     *                   challenge is
     * @param seed       the value persisted on the puzzle attempt
     */
    PuzzleChallenge create(MissionDifficulty difficulty, long seed);

    /**
     * How long the player has to answer, in seconds.
     *
     * <p>Returned to the client as {@code timeLimitSeconds} and used by the
     * server to compute {@code expiresAt}. The server decides whether the
     * window has passed; the client value is only for drawing a countdown.
     */
    int timeLimitSeconds(MissionDifficulty difficulty);
}