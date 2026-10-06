package com.cyberheist.energy;

import com.cyberheist.config.EnergyProperties;
import com.cyberheist.player.PlayerProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The regeneration arithmetic, tested against a clock the test controls.
 *
 * <p>Using a fixed {@link Clock} is what makes these cases checkable at all:
 * waiting five real minutes to prove {@code floor(elapsed / interval)} would be
 * a test nobody keeps.
 */
class EnergyServiceTest {

    private static final int MAXIMUM = 100;

    /** A clock the test can move; {@code Clock.fixed} cannot be advanced. */
    private MutableClock clock;

    private EnergyService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        service = new EnergyService(
                new EnergyProperties(MAXIMUM,
                        new EnergyProperties.Regeneration(true, 1, Duration.ofMinutes(5))),
                null,
                clock);
    }

    /** A profile starting at {@code energy}, stamped at the current test time. */
    private PlayerProfile profileAt(int energy) {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), UUID.randomUUID(),
                "tester", 1, 0, 0, energy);
        profile.setLastEnergyUpdate(clock.instant());
        return profile;
    }

    /** Moves the test clock forward and refreshes through the service. */
    private EnergySnapshot advanceAndRefresh(PlayerProfile profile, Duration elapsed) {
        clock.advance(elapsed);
        return service.refresh(profile);
    }

    /** Current test time. */
    private Instant now() {
        return clock.instant();
    }

    /**
     * A clock the test drives by hand.
     *
     * <p>Regeneration is defined in terms of elapsed real time, so testing it
     * honestly needs time to pass. Waiting five real minutes per case is not a
     * test anyone keeps, and compressing the interval would test the
     * configuration rather than the arithmetic, so the clock moves instead.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    @DisplayName("a new player starts at the configured balance")
    void startsFull() {
        assertThat(service.refresh(profileAt(100)).energy()).isEqualTo(100);
    }

    @Test
    @DisplayName("80 energy and 5 minutes gives 81")
    void oneIntervalAddsOne() {
        EnergySnapshot snapshot = advanceAndRefresh(profileAt(80), Duration.ofMinutes(5));
        assertThat(snapshot.energy()).isEqualTo(81);
    }

    @Test
    @DisplayName("80 energy and 20 minutes gives 84, one unit per interval")
    void severalIntervalsAddSeveral() {
        EnergySnapshot snapshot = advanceAndRefresh(profileAt(80), Duration.ofMinutes(20));
        assertThat(snapshot.energy()).isEqualTo(84);
    }

    @Test
    @DisplayName("80 energy and 100 minutes tops up to the cap")
    void longInactivityHitsTheCap() {
        EnergySnapshot snapshot = advanceAndRefresh(profileAt(80), Duration.ofMinutes(100));
        assertThat(snapshot.energy()).isEqualTo(MAXIMUM);
        assertThat(snapshot.isFull()).isTrue();
    }

    @Test
    @DisplayName("energy never exceeds the maximum, however long the gap")
    void neverExceedsTheMaximum() {
        assertThat(advanceAndRefresh(profileAt(95), Duration.ofDays(365)).energy())
                .isEqualTo(MAXIMUM);
    }

    @Test
    @DisplayName("energy never becomes negative")
    void neverGoesNegative() {
        // A negative regeneration credit is refused outright rather than
        // clamped: it can only come from a bug, and silently absorbing one would
        // hide it. The lower clamp below is the second line of defence.
        PlayerProfile profile = profileAt(20);
        assertThatThrownBy(() -> profile.applyRegeneration(-5, Duration.ZERO, MAXIMUM))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(profile.getEnergy()).isEqualTo(20);

        // And through the legitimate path, a drained player only ever climbs.
        PlayerProfile drained = profileAt(0);
        assertThat(advanceAndRefresh(drained, Duration.ofMinutes(60)).energy()).isEqualTo(12);
        assertThat(drained.getEnergy()).isNotNegative();
    }

    @Test
    @DisplayName("a partial interval regenerates nothing and keeps the remainder")
    void partialIntervalsCarryForward() {
        PlayerProfile profile = profileAt(50);

        // Four minutes short of an interval. A naive implementation that reset the
        // stamp to "now" on every read would never regenerate at all.
        advanceAndRefresh(profile, Duration.ofMinutes(4));
        assertThat(profile.getEnergy()).isEqualTo(50);

        // A sixth minute crosses one whole interval.
        advanceAndRefresh(profile, Duration.ofMinutes(2));
        assertThat(profile.getEnergy()).isEqualTo(51);

        // One minute of the new interval is preserved, so four more is enough
        // for the next unit - a stamp reset to "now" would need five.
        advanceAndRefresh(profile, Duration.ofMinutes(4));
        assertThat(profile.getEnergy()).isEqualTo(52);

        // And the next unit lands one interval after that.
        advanceAndRefresh(profile, Duration.ofMinutes(5));
        assertThat(profile.getEnergy()).isEqualTo(53);
    }

    @Test
    @DisplayName("time spent at the cap is not banked")
    void fullBalanceDoesNotBankRegeneration() {
        PlayerProfile profile = profileAt(MAXIMUM);

        // Sit at full for an hour, then spend it all.
        advanceAndRefresh(profile, Duration.ofMinutes(60));
        profile.spendEnergy(MAXIMUM);
        assertThat(profile.getEnergy()).isZero();

        // The next interval earns exactly one unit, not the sixty that were
        // earned while the player could not use any of it.
        assertThat(advanceAndRefresh(profile, Duration.ofMinutes(5)).energy()).isEqualTo(1);
    }

    @Test
    @DisplayName("spending and regenerating interleave correctly")
    void spendThenRegenerate() {
        PlayerProfile profile = profileAt(100);

        profile.spendEnergy(40);
        assertThat(profile.getEnergy()).isEqualTo(60);

        assertThat(advanceAndRefresh(profile, Duration.ofMinutes(15)).energy()).isEqualTo(63);

        profile.spendEnergy(3);
        assertThat(advanceAndRefresh(profile, Duration.ofMinutes(10)).energy()).isEqualTo(62);
    }

    @Test
    @DisplayName("a clock that jumps backwards grants nothing")
    void backwardsClockGrantsNothing() {
        PlayerProfile profile = profileAt(40);
        profile.setLastEnergyUpdate(clock.instant().plus(Duration.ofHours(2)));

        clock.advance(Duration.ofMinutes(5));
        EnergySnapshot snapshot = service.refresh(profile);

        assertThat(snapshot.energy())
                .as("a future timestamp must not be read as elapsed time")
                .isEqualTo(40);
        // Re-anchored, so the gap is not credited later either.
        assertThat(profile.getLastEnergyUpdate()).isEqualTo(clock.instant());
        assertThat(advanceAndRefresh(profile, Duration.ofMinutes(5)).energy()).isEqualTo(41);
    }

    @Test
    @DisplayName("regeneration can be switched off")
    void disabledRegenerationDoesNothing() {
        EnergyService disabled = new EnergyService(
                new EnergyProperties(MAXIMUM,
                        new EnergyProperties.Regeneration(false, 1, Duration.ofMinutes(5))),
                null,
                clock);

        PlayerProfile profile = profileAt(30);
        clock.advance(Duration.ofHours(10));

        EnergySnapshot snapshot = disabled.refresh(profile);
        assertThat(snapshot.energy()).isEqualTo(30);
        assertThat(snapshot.regenerationEnabled()).isFalse();
        assertThat(snapshot.nextRegenerationAt()).isNull();
    }

    @Test
    @DisplayName("a larger amount per interval is honoured")
    void honoursTheConfiguredAmount() {
        EnergyService fast = new EnergyService(
                new EnergyProperties(MAXIMUM,
                        new EnergyProperties.Regeneration(true, 3, Duration.ofMinutes(5))),
                null,
                clock);

        PlayerProfile profile = profileAt(50);
        assertThat(fast.refresh(profile).energy()).isEqualTo(50);

        clock.advance(Duration.ofMinutes(10));
        assertThat(fast.refresh(profile).energy()).isEqualTo(56);
    }

    @Test
    @DisplayName("the reported policy matches the configuration")
    void reportsThePolicy() {
        EnergySnapshot snapshot = advanceAndRefresh(profileAt(40), Duration.ofMinutes(1));

        assertThat(snapshot.maximum()).isEqualTo(MAXIMUM);
        assertThat(snapshot.regenerationAmount()).isEqualTo(1);
        assertThat(snapshot.regenerationIntervalSeconds()).isEqualTo(300);
        assertThat(snapshot.regenerationEnabled()).isTrue();
        // Four minutes of the current interval remain.
        assertThat(snapshot.nextRegenerationAt()).isEqualTo(now().plus(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("a profile with no stored stamp is anchored, not credited")
    void missingStampIsAnchored() {
        PlayerProfile profile = profileAt(40);
        profile.setLastEnergyUpdate(null);

        EnergySnapshot snapshot = service.refresh(profile);
        assertThat(snapshot.energy()).isEqualTo(40);
        assertThat(profile.getLastEnergyUpdate()).isEqualTo(now());

        clock.advance(Duration.ofMinutes(5));
        assertThat(service.refresh(profile).energy()).isEqualTo(41);
    }
}