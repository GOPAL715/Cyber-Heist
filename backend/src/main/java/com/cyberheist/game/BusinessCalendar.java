package com.cyberheist.game;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The one place a business date is decided.
 *
 * <p>"Today" is a question a browser must never be allowed to answer. A player
 * in a different timezone, or with a deliberately wrong device clock, would
 * otherwise be able to decide which daily challenges they are being shown and
 * when their streak resets. So the date is always derived here from an injected
 * server {@link Clock} and the configured business {@link ZoneId}.
 *
 * <p>Every daily calculation - which challenges exist, which counter row to read,
 * whether yesterday was consecutive, whether a streak milestone has been paid -
 * goes through this component, which is what keeps those decisions agreeing with
 * each other.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;
    private final ZoneId zone;

    /** Production constructor: the real clock and the configured business zone. */
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessCalendar(GameProperties properties) {
        this(Clock.systemUTC(), resolveZone(properties.timezone()));
    }

    /** Test constructor: a driven clock, so a test can place "now" on any day. */
    BusinessCalendar(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * Resolves a configured zone name, falling back to UTC.
     *
     * <p>An unresolvable zone is a configuration mistake. Refusing to start would
     * be defensible, but a wrong daily boundary is a far smaller problem than a
     * service that will not boot, and the fallback is stated in
     * {@link GameProperties#FALLBACK_TIMEZONE} rather than being silent.
     */
    private static ZoneId resolveZone(String name) {
        try {
            return ZoneId.of(name);
        } catch (RuntimeException invalidZone) {
            return ZoneId.of(GameProperties.FALLBACK_TIMEZONE);
        }
    }

    /** The business timezone, as configured. */
    public ZoneId zone() {
        return zone;
    }

    /** The current instant on the server clock. */
    public java.time.Instant now() {
        return clock.instant();
    }

    /** Today, in the business timezone. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * The instant the business day began.
     *
     * <p>Used to turn a dated total into a timestamp comparison for the tables
     * that store instants rather than dates.
     */
    public java.time.Instant startOfToday() {
        return today().atStartOfDay(zone).toInstant();
    }

    /** The instant the business day ended, for a half-open range. */
    public java.time.Instant startOfTomorrow() {
        return today().plusDays(1).atStartOfDay(zone).toInstant();
    }
}
