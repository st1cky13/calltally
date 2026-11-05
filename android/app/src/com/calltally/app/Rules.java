package com.calltally.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The rules for what counts and what gets uploaded. Plain Java with no Android parts, so it can be tested anywhere. */
final class Rules {
    private static final Pattern WORK_NAME = Pattern.compile("-\\s*Work\\s*$");
    private static final int MAX_CONTACT = 190;
    // The start of the year 10000, past which the server can't read the time.
    private static final long MAX_EPOCH = 253402300800L;

    private Rules() {}

    static boolean isWork(String name) {
        return name != null && WORK_NAME.matcher(name).find();
    }

    /** The last ten digits, so +1 (555) 123-4567 and 5551234567 match. Empty if there are no digits. */
    static String numberKey(String number) {
        if (number == null) return "";
        String digits = number.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** Who a conversation is with, or null when it has no work contact. */
    static final class Who {
        final String contact;
        final String number;
        Who(String contact, String number) {
            this.contact = contact;
            this.number = number;
        }
    }

    /**
     * addresses: the people in a conversation. Phones differ on whether this holds your own number, so one extra address
     * beside a single work contact counts as a chat with that contact. Calling it a group would give it a new identity and
     * count its texts a second time. Personal contacts appear only as a count.
     */
    static Who classify(long thread, List<String> addresses, Map<String, String> workNames) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> work = new ArrayList<>();
        String workNumber = null;
        int others = 0;
        for (String address : addresses) {
            String key = numberKey(address);
            if (key.isEmpty() || !seen.add(key)) continue;
            String name = workNames.get(key);
            if (name == null) {
                others++;
            } else {
                work.add(name);
                workNumber = address;
            }
        }
        if (work.isEmpty()) return null;
        if (work.size() == 1 && others <= 1) return new Who(work.get(0), workNumber);
        StringBuilder label = new StringBuilder("Group: ").append(String.join(", ", work));
        if (others > 0) label.append(" +").append(others);
        String text = label.length() > MAX_CONTACT ? label.substring(0, MAX_CONTACT) : label.toString();
        return new Who(text, "thread-" + thread);
    }

    static long toSeconds(long stored, boolean storedInSeconds) {
        // A phone may keep MMS times in milliseconds even though they're normally seconds.
        return storedInSeconds && stored < 100000000000L ? stored : stored / 1000L;
    }

    static String clockText(long epochSeconds) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(epochSeconds * 1000L));
    }

    static final class Entry {
        final String kind;
        final long epoch;
        final String at;
        final String contact;
        final String number;
        final String direction;
        final long duration;

        Entry(String kind, long epoch, String contact, String number, String direction, long duration) {
            this.kind = kind;
            this.epoch = epoch;
            this.at = clockText(epoch);
            this.contact = contact;
            this.number = number;
            this.direction = direction;
            this.duration = duration;
        }

        /** Same entry, same key. A new contact name changes it, so a rename reaches the server. */
        String key() {
            return kind + "|" + numberKey(number) + "|" + number.startsWith("thread-") + "|" + epoch + "|" + direction + "|" + contact;
        }

        /** The same limits the server applies. Anything outside them would make it refuse the whole batch. */
        boolean acceptable() {
            return epoch > 0 && epoch < MAX_EPOCH && !contact.trim().isEmpty() && contact.length() <= 200 && !number.trim().isEmpty()
                && number.length() <= 60 && duration >= 0 && duration <= 7 * 86400L;
        }

        String json() {
            return "{\"kind\":" + quote(kind) + ",\"at\":" + quote(at) + ",\"epoch\":" + epoch + ",\"contact\":" + quote(contact)
                + ",\"number\":" + quote(number) + ",\"direction\":" + quote(direction) + ",\"duration_s\":" + duration + "}";
        }
    }

    static String body(List<Entry> entries) {
        StringBuilder out = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            out.append(entries.get(i).json());
        }
        return out.append("]}").toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
