package com.cyberheist.daily;

import com.cyberheist.game.BusinessCalendar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * Picks which objectives a given day gets.
 *
 * <h2>The algorithm</h2>
 * The seed is {@code businessDate + ":" + dailySeedKey}, hashed to a long and used
 * to shuffle the pool. From that shuffled list the first {@code challengesPerDay}
 * entries become the day.
 *
 * <pre>
 * seed   = Objects.hash(businessDate, dailySeedKey)
 * pool   = active definitions, ordered by sortOrder
 * shuffled = Fisher-Yates over pool, driven by new Random(seed)
 * today = shuffled[0 .. challengesPerDay)
 * </pre>
 *
 * <p>Three properties follow, and each one is the reason for a specific choice:
 *
 * <ul>
 *   <li><b>Every player sees the same day.</b> The seed depends only on the date and
 *       a configured key, never on a user id, so two players cannot be shown
 *       different objectives.</li>
 *   <li><b>The same day is reproducible.</b> The rows are written once and then
 *       reused, so a rotation is not recomputed differently on a later request.</li>
 *   <li><b>Nothing random is authored.</b> Requirements come from the fixed pool, so
 *       a draw can never produce "defeat 3 bosses" for a level-1 account. That is
 *       why this shuffles a catalogue rather than generating objectives.</li>
 * </ul>
 *
 * <p>The configured seed key is the release valve: rotating it re-cuts the whole
 * rotation deterministically, without touching a row.
 */
@Component
public class DailyChallengeSelector {

    private final BusinessCalendar calendar;

    public DailyChallengeSelector(BusinessCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * The deterministic seed for a date.
     *
     * <p>{@link Long#hashCode()} mixes the two components, so a key change shifts
     * the whole rotation rather than nudging it.
     */
    long seedFor(LocalDate date, String dailySeedKey) {
        return 31L * (31L + date.toEpochDay()) + dailySeedKey.hashCode();
    }

    /**
     * Chooses today's objectives from the pool.
     *
     * <p>Distinct by construction: the pool rows are unique and the shuffle is a
     * permutation, so no code can appear twice. {@code UNIQUE (challenge_date, code)}
     * enforces the same thing underneath.
     *
     * <p>If the pool were ever smaller than a day's set, every available objective
     * is returned rather than failing or repeating - a short day is better than a
     * broken one.
     */
    public List<DailyChallengeDefinition> select(LocalDate date, String dailySeedKey,
                                                 List<DailyChallengeDefinition> pool, int perDay) {
        List<DailyChallengeDefinition> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random(seedFor(date, dailySeedKey)));
        int take = Math.min(perDay, shuffled.size());
        return List.copyOf(shuffled.subList(0, take));
    }

    /** Today's date, per the configured business timezone. */
    public LocalDate today() {
        return calendar.today();
    }
}
