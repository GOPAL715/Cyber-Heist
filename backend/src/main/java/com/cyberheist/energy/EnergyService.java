package com.cyberheist.energy;

import com.cyberheist.config.EnergyProperties;
import com.cyberheist.player.PlayerProfile;
import com.cyberheist.player.PlayerProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Passive, server-authoritative energy regeneration.
 *
 * <h2>Why there is no scheduler</h2>
 * Energy is derived from elapsed time, not accumulated by a job. A background
 * sweep would have to touch every player every minute to produce a number that
 * {@code floor(elapsed / interval) * amount} already yields exactly. Instead the
 * balance is brought up to date whenever player state is read or mutated, so an
 * idle player costs nothing and an active player is always charged against a
 * current figure.
 *
 * <h2>The algorithm</h2>
 * <pre>
 * elapsed            = now - lastEnergyUpdate
 * intervals          = floor(elapsed / interval)
 * regenerated        = intervals * amount
 * energy             = clamp(energy + regenerated, 0, maximum)
 * lastEnergyUpdate  += intervals * interval
 * </pre>
 *
 * <p>Two details are load-bearing. The timestamp advances by exactly the whole
 * intervals consumed rather than jumping to {@code now}, so the leftover
 * fraction carries forward instead of being dropped - otherwise a player
 * checking every four minutes would never regenerate at all. And it advances
 * even when the player was already at the cap, so time spent at full is not
 * banked and released later as a windfall.
 *
 * <h2>Trust</h2>
 * Every input is the server clock or a database column. Nothing here reads a
 * timestamp, an elapsed time or an energy value from a request, so a client
 * cannot fast-forward regeneration or grant itself energy.
 */
@Service
public class EnergyService {

    private static final Logger log = LoggerFactory.getLogger(EnergyService.class);

    private final EnergyProperties properties;
    private final PlayerProfileRepository profileRepository;
    private final Clock clock;

    /**
     * Spring entry point. The {@code Clock} overload exists for tests and is
     * deliberately not annotated, so Spring always takes this one.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public EnergyService(EnergyProperties properties,
                         PlayerProfileRepository profileRepository) {
        this(properties, profileRepository, Clock.systemUTC());
    }

    EnergyService(EnergyProperties properties,
                  PlayerProfileRepository profileRepository,
                  Clock clock) {
        this.properties = properties;
        this.profileRepository = profileRepository;
        this.clock = clock;
    }

    /**
     * Brings a managed profile's energy up to date.
     *
     * <p>Mutates the profile in place and returns the figures. It does not
     * persist: the caller's transaction decides whether the new balance is
     * written, which is what lets a read-only caller decline to store a no-op
     * refresh.
     *
     * <p>Every path that reads or changes energy must call this first,
     * otherwise a stale balance could be spent.
     */
    public EnergySnapshot refresh(PlayerProfile profile) {
        EnergyProperties.Regeneration policy = policy();
        Instant now = clock.instant();
        Duration interval = policy.intervalMinutes();

        if (profile.getLastEnergyUpdate() == null) {
            // Rows predating the Phase 3 migration, or a profile assembled in a
            // test: anchor the clock so the next refresh has something to measure.
            profile.setLastEnergyUpdate(now);
            return snapshot(profile, now, interval);
        }

        if (!policy.enabled()) {
            // Regeneration off: the balance still gets clamped to the cap so a
            // balance above maximum can never survive.
            profile.applyRegeneration(0, Duration.ZERO, properties.maximum());
            return snapshot(profile, now, interval);
        }

        Duration elapsed = Duration.between(profile.getLastEnergyUpdate(), now);
        if (elapsed.isNegative()) {
            // The stored stamp is ahead of our clock, which can only mean the
            // system clock moved backwards. Nothing is owed, and re-anchoring
            // stops the gap being credited later.
            log.warn("Energy timestamp is in the future for profile {}; re-anchoring",
                    profile.getId());
            profile.setLastEnergyUpdate(now);
            return snapshot(profile, now, interval);
        }

        long intervals = elapsed.toMillis() / interval.toMillis();
        if (intervals <= 0) {
            return snapshot(profile, now, interval);
        }

        int regenerated = (int) Math.min(intervals, Integer.MAX_VALUE) * policy.amount();
        // Advance by exactly the consumed intervals, never to "now": the
        // remaining fraction of the current interval has to survive.
        Duration consumed = interval.multipliedBy(intervals);

        int before = profile.getEnergy();
        int after = profile.applyRegeneration(regenerated, consumed, properties.maximum());
        if (after != before) {
            log.debug("Energy regenerated for profile {}: {} -> {} (+{} after {})",
                    profile.getId(), before, after, after - before, consumed);
        }
        return snapshot(profile, now, interval);
    }

    /**
     * Refreshes and persists a player's energy.
     *
     * <p>Used by read paths such as {@code GET /player/profile}, so the figure
     * the player sees is the figure the server will charge against.
     */
    @Transactional
    public Optional<EnergySnapshot> currentFor(UUID userId) {
        Optional<PlayerProfile> found = profileRepository.findByUserId(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PlayerProfile profile = found.get();
        EnergySnapshot snapshot = refresh(profile);
        profileRepository.save(profile);
        return Optional.of(snapshot);
    }

    /**
     * Locks a profile for update and refreshes its energy.
     *
     * <p>The pessimistic write lock is what makes concurrent spending safe: two
     * simultaneous mission starts are serialised, so the second observes the
     * energy the first already deducted instead of a stale balance. Without it
     * both could see 10 energy and both spend it.
     *
     * <p>Must be called inside a transaction; the lock lasts until it commits.
     */
    public PlayerProfile lockAndRefresh(UUID userId) {
        PlayerProfile profile = profileRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Player profile not found for user " + userId));
        refresh(profile);
        return profile;
    }

    /** Current figures for a profile the caller has already refreshed. */
    public EnergySnapshot describe(PlayerProfile profile) {
        return snapshot(profile, clock.instant(), policy().intervalMinutes());
    }

    /**
     * When the next unit is due.
     *
     * <p>Measured from {@code lastEnergyUpdate}, not from "now", because that
     * stamp is what the next refresh will actually compare against.
     */
    private EnergySnapshot snapshot(PlayerProfile profile, Instant now, Duration interval) {
        EnergyProperties.Regeneration policy = policy();
        Instant next = null;
        if (policy.enabled() && interval.isPositive()) {
            Instant last = profile.getLastEnergyUpdate();
            long sinceLast = last == null ? 0L : now.toEpochMilli() - last.toEpochMilli();
            long remaining;
            if (sinceLast < 0) {
                remaining = interval.toMillis();
            } else if (sinceLast >= interval.toMillis()) {
                // Already overdue; the next refresh will credit it immediately.
                remaining = 0;
            } else {
                remaining = interval.toMillis() - sinceLast;
            }
            next = now.plusMillis(remaining);
        }
        return new EnergySnapshot(
                profile.getEnergy(),
                properties.maximum(),
                policy.enabled(),
                policy.amount(),
                policy.intervalMinutes().toSeconds(),
                next);
    }

    private EnergyProperties.Regeneration policy() {
        return properties.regeneration();
    }
}