package com.calltally.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * The record's one rule for ids and repeats, a copy of the server's entry_id, number_key and Store.add in calltally.py.
 * Plain Java with no Android parts: Record keeps the rows in SQLite, and the checks keep them in memory, through the
 * same add().
 */
final class Ledger {
    private Ledger() {}

    /** One row of the record, as the server stored it. */
    static final class Row {
        final String id;
        final String kind;
        final long epoch;
        final String at;
        final String day;
        final String contact;
        final String number;
        final String numberKey;
        final String direction;
        final long durationS;

        Row(Rules.Entry entry) {
            String number = entry.number;
            this.id = entryId(entry.kind, number, entry.epoch, entry.direction);
            this.kind = entry.kind;
            this.epoch = entry.epoch;
            this.at = entry.at;
            this.day = entry.at.substring(0, 10);
            this.contact = strip(entry.contact);
            this.number = strip(number);
            this.numberKey = numberKey(number);
            this.direction = entry.direction;
            // A text has no length.
            this.durationS = "text".equals(entry.kind) ? 0 : entry.duration;
        }
    }

    /** Where rows are kept. find gives the id of a row this one repeats, or null. */
    interface Book {
        String find(Row row);
        void insert(Row row);
        void rename(String id, String contact);
    }

    /** What one add did. */
    static final class Added {
        int added;
        int repeats;
        int unusable;
    }

    /**
     * Adds entries the way the server's Store.add did. A row repeats one already kept if it has the same id, or the same
     * kind, number key, clock time and direction: the first covers a change of time zone, the second a run that read the
     * phone's zone wrongly. A repeat only refreshes the contact's name; it never brings back an ignored entry and keeps
     * the time and day first recorded. Entries outside the server's limits are left out and counted.
     */
    static Added add(Book book, List<Rules.Entry> entries) {
        Added out = new Added();
        for (Rules.Entry entry : entries) {
            if (!entry.acceptable()) {
                out.unusable++;
                continue;
            }
            Row row = new Row(entry);
            String found = book.find(row);
            if (found != null) {
                book.rename(found, row.contact);
                out.repeats++;
            } else {
                book.insert(row);
                out.added++;
            }
        }
        return out;
    }

    /** The server's number_key: a group is known by its conversation; otherwise the last ten digits, or the text itself. */
    static String numberKey(String number) {
        if (number.startsWith("thread-")) return number;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < number.length(); ) {
            int c = number.codePointAt(i);
            // Python's \d: any decimal digit, not only 0 to 9.
            if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER) digits.appendCodePoint(c);
            i += Character.charCount(c);
        }
        if (digits.length() == 0) return strip(number).toLowerCase(Locale.ROOT);
        int count = digits.codePointCount(0, digits.length());
        return count > 10 ? digits.substring(digits.offsetByCodePoints(0, count - 10)) : digits.toString();
    }

    /** The server's entry_id: the start of the SHA-256 of kind, number key, moment and direction. */
    static String entryId(String kind, String number, long epoch, String direction) {
        String raw = kind + "|" + numberKey(number) + "|" + epoch + "|" + direction;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Python's str.strip(): white space and the other space characters at both ends. */
    static String strip(String text) {
        int start = 0, end = text.length();
        while (start < end && space(text.charAt(start))) start++;
        while (end > start && space(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }
}
