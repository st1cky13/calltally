package com.calltally.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * How the screens say times, days and counts, in the page's words. Plain Java with no Android parts, so the checks can
 * print the same words. Call times are always exact, never rounded.
 */
final class Words {
    private Words() {}

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    /** The compact form for a day's cell: 17:06 or 1:08:17. */
    static String clockTime(long s) {
        long h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        return h > 0 ? h + ":" + two(m) + ":" + two(sec) : m + ":" + two(sec);
    }

    /** The full form: 1h 09m 39s, 19m 43s or 47s. */
    static String fullTime(long s) {
        long h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        if (h > 0) return h + "h " + two(m) + "m " + two(sec) + "s";
        if (m > 0) return m + "m " + two(sec) + "s";
        return sec + "s";
    }

    static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** "16 calls · 32 texts", for the line under a call time already shown large. */
    static String counts(long calls, long texts) {
        return plural(calls, "call") + " · " + plural(texts, "text");
    }

    /** The time the texts are given by the owner's rule: so many seconds each, kept apart from call time. "2m 15s" */
    static String textTime(long texts, long seconds) {
        return fullTime(texts * seconds);
    }

    /** The rule's seconds per text from its saved words: 0, which is off, when unset, unreadable or below 0. */
    static int textSeconds(String saved) {
        if (saved == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(saved.trim()));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /** "Time per text: off" or "Time per text: 15 seconds", for Options and its review line. */
    static String textRule(int seconds) {
        return seconds <= 0 ? "off" : plural(seconds, "second");
    }

    /** "3:08 PM" from a recorded clock time, yyyy-MM-dd HH:mm:ss. */
    static String clock(String at) {
        int h = Integer.parseInt(at.substring(11, 13));
        return (h % 12 == 0 ? 12 : h % 12) + ":" + at.substring(14, 16) + (h < 12 ? " AM" : " PM");
    }

    static String monthName(int month) {
        return java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.US);
    }

    /** "November 2026" */
    static String monthTitle(int year, int month) {
        return monthName(month) + " " + year;
    }

    /** "Oct 25", or "Oct 25, 2027" with the year. */
    static String shortDay(LocalDate day, boolean withYear) {
        String text = monthName(day.getMonthValue()).substring(0, 3) + " " + day.getDayOfMonth();
        return withYear ? text + ", " + day.getYear() : text;
    }

    /** "Sun Oct 25", with the year when it isn't this year. */
    static String weekdayDay(LocalDate day, LocalDate today) {
        return weekday(day).substring(0, 3) + " " + shortDay(day, day.getYear() != today.getYear());
    }

    static String weekday(LocalDate day) {
        return day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
    }

    /** "Tuesday, November 3, 2026" */
    static String longDay(LocalDate day) {
        return weekday(day) + ", " + monthName(day.getMonthValue()) + " " + day.getDayOfMonth() + ", " + day.getYear();
    }

    /** The same test as the record's: only a call that connected counts. */
    static boolean connected(String kind, String direction, long durationS) {
        return "call".equals(kind) && ("in".equals(direction) || "out".equals(direction)) && durationS > 0;
    }

    /** "3:08 PM · outgoing call", "4:16 PM · text received", with when a call past midnight ended. */
    static String status(String kind, String direction, String at, long durationS) {
        if (!"call".equals(kind)) return clock(at) + " · " + ("in".equals(direction) ? "text received" : "text sent");
        boolean counted = connected(kind, direction, durationS);
        String named;
        if ("out".equals(direction) && !counted) named = "outgoing call, no answer";
        else if ("in".equals(direction)) named = "incoming call";
        else if ("out".equals(direction)) named = "outgoing call";
        else if ("missed".equals(direction)) named = "missed call";
        else if ("rejected".equals(direction)) named = "declined call";
        else named = "call";
        return clock(at) + " · " + named + (counted ? ended(at, durationS) : "");
    }

    /** A call that runs past midnight shows when it ended, worked out from the start and length on the local clock. */
    static String ended(String at, long durationS) {
        LocalDateTime start = LocalDateTime.parse(at, AT);
        LocalDateTime end = start.plusSeconds(durationS);
        if (end.toLocalDate().equals(start.toLocalDate())) return "";
        String time = clock(end.format(AT));
        long days = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
        return days == 1 ? " · ended " + time
            : " · ended " + shortDay(end.toLocalDate(), end.getYear() != start.getYear()) + ", " + time;
    }

    /** The header's "Updated 4 min ago". */
    static String updated(long lastMs, long nowMs) {
        if (lastMs <= 0) return "Not updated yet";
        long mins = Math.max(0, Math.round((nowMs - lastMs) / 60000.0));
        if (mins < 1) return "Updated just now";
        if (mins < 60) return "Updated " + mins + " min ago";
        if (mins < 2880) return "Updated " + Math.round(mins / 60.0) + " hr ago";
        return "Updated " + Math.round(mins / 1440.0) + " days ago";
    }

    /** 0 fresh, 1 more than three hours old, 2 a day old or more, or never. */
    static int staleness(long lastMs, long nowMs) {
        if (lastMs <= 0) return 1;
        long mins = Math.max(0, Math.round((nowMs - lastMs) / 60000.0));
        return mins >= 1440 ? 2 : mins > 180 ? 1 : 0;
    }

    private static String two(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
