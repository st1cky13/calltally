package com.calltally.app;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Pay periods: they start on a chosen day and run a fixed number of days, one after another, on local dates. Period k
 * starts start + k × days, so every day falls in exactly one period, before the start day too. Plain Java, so the checks
 * test it.
 */
final class PayPeriod {
    final LocalDate start;
    final int days;

    PayPeriod(LocalDate start, int days) {
        if (days < 1) throw new IllegalArgumentException("days");
        this.start = start;
        this.days = days;
    }

    /** From the record's settings, or null when none is set or it can't be read. */
    static PayPeriod of(String start, String days) {
        if (start == null) return null;
        try {
            int length = days == null ? 14 : Integer.parseInt(days.trim());
            return length < 1 ? null : new PayPeriod(LocalDate.parse(start.trim()), length);
        } catch (DateTimeParseException | NumberFormatException error) {
            return null;
        }
    }

    /** The number of the period holding this day. */
    long index(LocalDate day) {
        return Math.floorDiv(ChronoUnit.DAYS.between(start, day), (long) days);
    }

    LocalDate first(long k) {
        return start.plusDays(k * days);
    }

    LocalDate last(long k) {
        return first(k).plusDays(days - 1);
    }

    /** "Pay period Oct 25 – Nov 7", with years when it isn't all in this year. */
    String label(long k, LocalDate today) {
        LocalDate from = first(k), to = last(k);
        boolean showYear = from.getYear() != today.getYear() || to.getYear() != today.getYear();
        return "Pay period " + Words.shortDay(from, showYear && from.getYear() != to.getYear()) + " – " + Words.shortDay(to, showYear);
    }

    /** "Pay periods start Sun Oct 25 and run two weeks" */
    String describe(LocalDate today) {
        String length = days == 14 ? "two weeks" : days == 7 ? "one week" : Words.plural(days, "day");
        return "Pay periods start " + Words.weekdayDay(start, today) + " and run " + length;
    }
}
