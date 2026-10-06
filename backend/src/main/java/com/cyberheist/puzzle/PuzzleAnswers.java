package com.cyberheist.puzzle;

import java.util.Locale;

/**
 * Compares a submitted answer with the one a provider derived.
 *
 * <p>Players type with different habits - trailing spaces, lower case, a
 * trailing full stop, {@code 032} for thirty-two. Normalising before comparing
 * removes accidental failures without loosening anything meaningful: the
 * accepted set is still exactly the single answer the provider generated.
 *
 * <p>Numeric answers are compared by value rather than by spelling, so
 * {@code "32"}, {@code "32.0"} and {@code " 32 "} are all the same answer.
 */
final class PuzzleAnswers {

    private PuzzleAnswers() {
    }

    /**
     * Canonical form of a submitted or expected answer.
     *
     * <p>Trims, collapses internal whitespace, drops a trailing sentence
     * period and upper-cases. Returns an empty string for {@code null}, which
     * therefore never matches a real answer.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip().replaceAll("\\s+", " ");
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * True when {@code submitted} is the {@code expected} answer.
     *
     * <p>Falls back to a numeric comparison when both sides parse as numbers,
     * so a zero-padded or decimal-typed answer is not rejected for cosmetic
     * reasons.
     */
    static boolean matches(String submitted, String expected) {
        String given = normalise(submitted);
        if (given.isEmpty()) {
            return false;
        }
        String wanted = normalise(expected);
        if (given.equals(wanted)) {
            return true;
        }
        Double givenNumber = parseNumber(given);
        Double wantedNumber = parseNumber(wanted);
        return givenNumber != null && wantedNumber != null && givenNumber.equals(wantedNumber);
    }

    /** Parses a plain decimal number, or returns {@code null} if it is not one. */
    private static Double parseNumber(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}