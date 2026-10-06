package com.cyberheist.puzzle;

import com.cyberheist.exception.PuzzleProviderUnavailableException;
import com.cyberheist.mission.MissionDifficulty;
import com.cyberheist.puzzle.dto.PuzzleChallengeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The puzzle engine's front door: owns the provider registry, generation and
 * answer validation.
 *
 * <p><strong>Adding a puzzle type requires no change here or in
 * {@code MissionService}.</strong> Every {@link PuzzleProvider} on the classpath
 * is collected at startup and indexed by its {@link PuzzleType}, so a new type
 * is one new {@code @Component}. There is no switch on puzzle type outside the
 * providers themselves.
 *
 * <h2>Where the answer lives</h2>
 * The challenge is a pure function of {@code (type, difficulty, seed)}, and only
 * the seed is persisted. Validating a submission re-derives the challenge and
 * compares against the freshly derived answer. Consequences worth stating
 * plainly:
 * <ul>
 *   <li>The answer is never written to the database, so a dump cannot leak it.</li>
 *   <li>The answer is never sent to the client, so devtools cannot reveal it.</li>
 *   <li>A provider must stay deterministic; the {@link PuzzleProvider} contract
 *       spells that out because the whole design rests on it.</li>
 * </ul>
 *
 * <p>This service is deliberately free of XP, coins, energy and mission state:
 * it answers "is this right?", and nothing else.
 */
@Service
public class PuzzleService {

    private static final Logger log = LoggerFactory.getLogger(PuzzleService.class);

    private final Map<PuzzleType, PuzzleProvider> providers = new EnumMap<>(PuzzleType.class);

    private final Clock clock;

    /**
     * Spring entry point. The {@code Clock} overload exists for tests and is
     * deliberately not annotated, so Spring always takes this one.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PuzzleService(List<PuzzleProvider> discoveredProviders) {
        this(discoveredProviders, Clock.systemUTC());
    }

    PuzzleService(List<PuzzleProvider> discoveredProviders, Clock clock) {
        this.clock = clock;
        for (PuzzleProvider provider : discoveredProviders) {
            PuzzleProvider previous = providers.put(provider.type(), provider);
            if (previous != null) {
                // Two beans claiming one type is a wiring mistake, not a
                // recoverable condition: failing fast beats silent
                // non-determinism at runtime.
                throw new IllegalStateException(
                        "Two PuzzleProvider beans claim type " + provider.type()
                                + ": " + previous.getClass().getSimpleName()
                                + " and " + provider.getClass().getSimpleName());
            }
        }
        // Every declared type must be covered, otherwise a mission referencing
        // it would fail only when a player happened to start that mission.
        for (PuzzleType type : PuzzleType.values()) {
            if (!providers.containsKey(type)) {
                throw new IllegalStateException("No PuzzleProvider is registered for type " + type);
            }
        }
        log.info("Puzzle engine ready with providers: {}", providers.keySet());
    }

    /**
     * Builds a challenge for a mission.
     *
     * <p>The seed is drawn here and handed to the provider; only the seed is
     * persisted afterwards.
     */
    public GeneratedPuzzle generate(PuzzleType type, MissionDifficulty difficulty) {
        return regenerate(type, difficulty, newSeed());
    }

    /**
     * Rebuilds a challenge from a persisted seed.
     *
     * <p>This is what makes validation possible without storing an answer, and
     * what lets the puzzle screen be re-read after a page reload.
     */
    public GeneratedPuzzle regenerate(PuzzleType type, MissionDifficulty difficulty, long seed) {
        PuzzleProvider provider = require(type);
        PuzzleChallenge challenge = provider.create(difficulty, seed);
        Duration window = Duration.ofSeconds(provider.timeLimitSeconds(difficulty));
        return new GeneratedPuzzle(seed, challenge, window);
    }

    /**
     * The answer to {@code submitted}, judged on the server.
     *
     * <p>The challenge is re-derived from the stored seed first, so the value
     * compared against never came from the request.
     *
     * @param submitted what the player typed; never inspected for meaning beyond
     *                  the comparison itself
     * @return true only for the generated answer, allowing for the harmless
     *         formatting differences {@link PuzzleAnswers} normalises
     */
    public boolean isCorrect(PuzzleType type, MissionDifficulty difficulty, long seed, String submitted) {
        PuzzleChallenge challenge = require(type).create(difficulty, seed);
        return PuzzleAnswers.matches(submitted, challenge.expectedAnswer());
    }

    /** The client-safe projection of a stored attempt, re-derived from its seed. */
    public PuzzleChallengeView viewOf(PuzzleAttempt attempt) {
        Instant startedAt = attempt.getStartedAt();
        Instant expiresAt = attempt.getExpiresAt();
        return regenerate(attempt.getPuzzleType(), attempt.getDifficulty(), attempt.getSeed())
                .viewOf(attempt.getPuzzleId(), attempt.getDifficulty(), startedAt, expiresAt);
    }

    /** Every registered type, used by tests and by future admin tooling. */
    public List<PuzzleType> supportedTypes() {
        return List.copyOf(providers.keySet());
    }

    /** Server time. All timing decisions use this, never a client clock. */
    public Instant now() {
        return clock.instant();
    }

    private PuzzleProvider require(PuzzleType type) {
        PuzzleProvider provider = providers.get(type);
        if (provider == null) {
            throw new PuzzleProviderUnavailableException(
                    "No puzzle provider is registered for type " + type);
        }
        return provider;
    }

    /**
     * A fresh seed.
     *
     * <p>{@code SecureRandom} rather than {@code Random}: the seed is the only
     * unpredictable input to generation, and a guessable one would let a client
     * regenerate the puzzle locally and read the answer off the objects it
     * builds. Everything else is derived deterministically from it, which is
     * exactly what makes the server's later re-derivation reliable.
     */
    private static long newSeed() {
        return SEED_SOURCE.nextLong();
    }

    /**
     * One shared secure generator.
     *
     * <p>{@code SecureRandom} is thread-safe and self-seeding, and a single
     * instance avoids repeatedly paying for OS entropy under concurrent starts.
     */
    private static final java.security.SecureRandom SEED_SOURCE = new java.security.SecureRandom();
}